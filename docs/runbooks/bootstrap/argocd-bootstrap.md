# ArgoCD Bootstrap (one-time)

Purpose: install ArgoCD in the k3s cluster so GitOps can manage k8s apps.

## Prerequisites
- k3s server is running and reachable via SSM.
- Control-plane taint is enforced (`dedicated=control-plane:NoSchedule`) so only platform pods (ArgoCD/ESO/kube-system) schedule on the server.
- AWS CLI configured with permissions to run SSM commands:
  - `ssm:SendCommand`
  - `ssm:GetCommandInvocation`
  - `ssm:DescribeInstanceInformation`
- `scripts/bootstrap-argocd-install.sh` and `scripts/bootstrap-argocd-app.sh` are executable.
- Repo-server resources and probe timeouts are tuned via `scripts/argocd-values.yaml` (used by the bootstrap script).
- If using `--env`, instances must be tagged with:
  - `Role=k3s-server`
  - `Environment=<env>`
  - `Project=<project>` (optional when `--project` is provided)

## Steps
1) Get the k3s server instance ID (from tags):
```bash
aws ec2 describe-instances \
  --filters "Name=tag:Name,Values=cloudradar-<env>-k3s-server" "Name=instance-state-name,Values=running" \
  --query "Reservations[].Instances[].InstanceId" \
  --output text
```

2) Install ArgoCD (uses SSM):
```bash
scripts/bootstrap-argocd-install.sh <instance-id> us-east-1
```

Optional: resolve the instance by tags instead of passing an ID:
```bash
scripts/bootstrap-argocd-install.sh --env <env> --project cloudradar --region us-east-1
```

Optional overrides (all have defaults):
```bash
ARGOCD_NAMESPACE=argocd \
ARGOCD_CHART_VERSION=9.3.4 \
scripts/bootstrap-argocd-install.sh <instance-id> us-east-1
```

3) Create the platform root Application (ESO + platform components):
```bash
WAIT_CRDS=externalsecrets.external-secrets.io,clustersecretstores.external-secrets.io,secretstores.external-secrets.io \
ARGOCD_APP_NAME=cloudradar-platform \
ARGOCD_APP_NAMESPACE=argocd \
ARGOCD_APP_PATH=k8s/platform \
scripts/bootstrap-argocd-app.sh <instance-id> us-east-1
```

4) Create the apps root Application (workloads):
```bash
IGNORE_INGESTER_REPLICAS=true \
ARGOCD_APP_NAME=cloudradar \
ARGOCD_APP_NAMESPACE=cloudradar \
ARGOCD_APP_PATH=k8s/apps \
scripts/bootstrap-argocd-app.sh <instance-id> us-east-1
```

Optional overrides (all have defaults):
```bash
ARGOCD_NAMESPACE=argocd \
ARGOCD_APP_REPO=https://github.com/ClementV78/CloudRadar.git \
ARGOCD_APP_REVISION=main \
scripts/bootstrap-argocd-app.sh <instance-id> us-east-1
```

5) Fetch the ArgoCD admin password (optional):
```bash
aws ssm send-command \
  --instance-ids <instance-id> \
  --document-name AWS-RunShellScript \
  --parameters commands='["export KUBECONFIG=/etc/rancher/k3s/k3s.yaml","sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath=\"{.data.password}\" | base64 -d; echo"]' \
  --output text
```
The admin credential is stored in the `argocd-initial-admin-secret` Secret (namespace `argocd`).

6) Check the ArgoCD Application status (optional):
```bash
aws ssm send-command \
  --instance-ids <instance-id> \
  --document-name AWS-RunShellScript \
  --parameters commands='["export KUBECONFIG=/etc/rancher/k3s/k3s.yaml","sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl -n argocd get applications"]' \
  --output text
```

## Local access (without installing ArgoCD CLI)

When you don't want to install `argocd` locally, use the CLI inside the `argocd-server` pod:

```bash
export KUBECONFIG=$HOME/k3s.yaml   # adjust to your kubeconfig path
POD=$(kubectl -n argocd get pod -l app.kubernetes.io/name=argocd-server -o jsonpath='{.items[0].metadata.name}')
PASS=$(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d)

printf "y\n" | kubectl -n argocd exec "$POD" -c server -- \
  /usr/local/bin/argocd login argocd-server.argocd.svc:443 \
  --insecure --username admin --password "$PASS" --grpc-web
```

Sync order (ESO):
```bash
kubectl -n argocd exec "$POD" -c server -- /usr/local/bin/argocd app sync external-secrets-operator
kubectl -n argocd exec "$POD" -c server -- /usr/local/bin/argocd app sync external-secrets-config
kubectl -n argocd exec "$POD" -c server -- /usr/local/bin/argocd app sync cloudradar --prune
```

Troubleshooting:
- If `container not found ("server")`, list containers with:
  `kubectl -n argocd get pod "$POD" -o jsonpath='{.spec.containers[*].name}'`
- If `connection refused`, wait for the pod to be Ready:
  `kubectl -n argocd wait --for=condition=Ready pod -l app.kubernetes.io/name=argocd-server --timeout=300s`

## Script mapping
- Step 2 (`bootstrap-argocd-install.sh`) runs the Helm-based install:
  - install Helm if missing
  - `helm upgrade --install` ArgoCD (optionally pinned chart version)
  - apply tolerations/nodeSelector so ArgoCD can run on the tainted control-plane
  - wait for Application CRD to be established
  - wait for `argocd-server` deployment
  - list pods for quick verification
- Step 3 (`bootstrap-argocd-app.sh` with `k8s/platform`) creates `cloudradar-platform`
  - installs ESO (CRDs via sync wave 0)
  - applies ESO config (sync wave 1)
- Step 4 (`bootstrap-argocd-app.sh` with `k8s/apps`) creates `cloudradar`
  - includes optional ignoreDifferences for ingester replicas

Note: the script exports `KUBECONFIG=/etc/rancher/k3s/k3s.yaml` and preserves it when running `sudo`.

## Replica overrides for ingester
The apps bootstrap can configure ArgoCD to ignore `spec.replicas` for the `ingester` deployment.
This allows the admin scale API to change replicas without ArgoCD reverting them.

If the Application already exists, you can patch it manually:
```bash
kubectl -n argocd patch app cloudradar --type merge -p '{
  "spec": {
    "ignoreDifferences": [
      {
        "group": "apps",
        "kind": "Deployment",
        "name": "ingester",
        "namespace": "cloudradar",
        "jsonPointers": ["/spec/replicas"]
      }
    ]
  }
}'
```

## Notes
- This is a one-time bootstrap. After that, ArgoCD manages k8s apps via GitOps.
- Keep ArgoCD access private; prefer port-forwarding over public exposure.
- If `k8s/apps` has no manifests, the Application can report `Missing` or `OutOfSync` until content is added.
