# ArgoCD Bootstrap (one-time)

Purpose: install ArgoCD in the k3s cluster so GitOps can manage k8s apps.

## Prerequisites
- k3s server is running and reachable via SSM.
- AWS CLI configured with permissions to run SSM commands:
  - `ssm:SendCommand`
  - `ssm:GetCommandInvocation`
  - `ssm:DescribeInstanceInformation`
- `scripts/bootstrap-argocd.sh` is executable.
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

2) Run the bootstrap script (uses SSM):
```bash
scripts/bootstrap-argocd.sh <instance-id> us-east-1 3.2.5
```

Optional: resolve the instance by tags instead of passing an ID:
```bash
scripts/bootstrap-argocd.sh --env <env> --project cloudradar us-east-1 3.2.5
```

Optional overrides (all have defaults):
```bash
ARGOCD_NAMESPACE=argocd \
ARGOCD_APP_NAME=cloudradar \
ARGOCD_APP_NAMESPACE=cloudradar \
ARGOCD_APP_REPO=https://github.com/ClementV78/CloudRadar.git \
ARGOCD_APP_PATH=k8s/apps \
ARGOCD_APP_REVISION=main \
scripts/bootstrap-argocd.sh <instance-id> us-east-1 3.2.5
```

3) Fetch the ArgoCD admin password (optional):
```bash
aws ssm send-command \
  --instance-ids <instance-id> \
  --document-name AWS-RunShellScript \
  --parameters commands='["sudo kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath=\"{.data.password}\" | base64 -d; echo"]' \
  --output text
```
The admin credential is stored in the `argocd-initial-admin-secret` Secret (namespace `argocd`).

4) Check the ArgoCD Application status (optional):
```bash
aws ssm send-command \
  --instance-ids <instance-id> \
  --document-name AWS-RunShellScript \
  --parameters commands='["sudo kubectl -n argocd get applications"]' \
  --output text
```

## Script mapping
- Step 2 runs the exact kubectl actions:
  - create namespace `argocd`
  - apply ArgoCD install manifest (pinned version)
  - wait for Application CRD to be established
  - wait for `argocd-server` deployment
  - list pods for quick verification
  - create ArgoCD Application `cloudradar` (repo `k8s/apps` -> namespace `cloudradar`)

## Notes
- This is a one-time bootstrap. After that, ArgoCD manages k8s apps via GitOps.
- Keep ArgoCD access private; prefer port-forwarding over public exposure.
- If `k8s/apps` has no manifests, the Application can report `Missing` or `OutOfSync` until content is added.
