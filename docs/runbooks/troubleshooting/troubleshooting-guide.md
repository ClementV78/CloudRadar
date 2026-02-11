# Troubleshooting Guide (AWS + k3s)

Purpose: fast diagnosis of the most common failures in the CloudRadar stack.

> Rule of thumb: start with **cluster health**, then **networking**, then **workloads**.

---

## Table of Contents

1. Quick Triage (Decision Tree)
2. Fast Checks (Run First)
3. Cluster & Nodes
4. Networking & Traffic
5. GitOps & Supply Chain
6. Workloads & Runtime
7. Storage
8. Utilities
9. When to Escalate

---

## 1) Quick Triage (Decision Tree)

```mermaid
flowchart TB
  start([Incident]) --> nodes{Nodes Ready?}
  nodes -->|No| nodefix[Node / k3s health checks]
  nodes -->|Yes| sched{Pods Pending?}
  sched -->|Yes| schedfix[Taints / tolerations / selectors]
  sched -->|No| net{Can reach service internally?}
  net -->|No| netfix[Service / DNS / Network]
  net -->|Yes| edge{Edge / Ingress reachable?}
  edge -->|No| edgefix[Edge + SG + Traefik]
  edge -->|Yes| app{Workload healthy?}
  app -->|No| appfix[Pods / probes / images / secrets]
  app -->|Yes| storage{PVC/Mount OK?}
  storage -->|No| storagefix[StorageClass / CSI / affinity]
  storage -->|Yes| argocd{ArgoCD sync OK?}
  argocd -->|No| gitopsfix[ArgoCD / repo / chart]
  gitopsfix --> crds{CRDs missing?}
  crds -->|Yes| crdsfix[Install/upgrade CRDs]
  crds -->|No| gitopsfix2[Repo / values / path / auth]
  argocd -->|Yes| done([Monitor / close incident])
```

---

## 2) Fast Checks (Run First)

```bash
# Cluster state
kubectl get nodes -o wide
kubectl get pods -A --field-selector=status.phase!=Running

# Core infra
kubectl -n kube-system get pods -o wide
kubectl -n kube-system get svc -o wide

# Ingress + routes
kubectl get ingress -A
kubectl -n kube-system get pods -l app.kubernetes.io/name=traefik -o wide
kubectl -n kube-system get svc -l app.kubernetes.io/name=traefik -o wide

# Critical namespaces
kubectl get pods -n argocd -o wide
kubectl get pods -n monitoring -o wide
kubectl get pods -n external-secrets -o wide

# Recent cluster events
kubectl get events -A --sort-by=.lastTimestamp | tail -n 50

# ArgoCD app health
kubectl -n argocd get applications

# Services with no endpoints
kubectl get svc -A -o wide | awk 'NR==1 || $5=="<none>"'
kubectl get endpoints -A | awk 'NR==1 || $2=="<none>"'
```

---

## 3) Cluster & Nodes

### 3.1 Node / k3s Health (Nodes NotReady)

**Symptoms**: `NotReady`, pods stuck `Pending`, high restart count.

**Checks**
```bash
kubectl describe node <node>
ssh <node> 'sudo systemctl status k3s'  # server
ssh <node> 'sudo systemctl status k3s-agent'  # worker
ssh <node> 'sudo journalctl -u k3s -n 200'
```

**Likely causes & fixes**
- **k3s service crashed** -> restart service; check disk pressure, cgroup, kubelet logs.
- **No disk / inode** -> expand volume or clean containerd.
- **CNI down** -> check flannel/traefik pods in kube-system.

---

### 3.2 Environment Teardown / No Nodes

**Symptoms**: `kubectl get nodes` returns nothing, API errors, edge endpoints all down.

**Checks**
```bash
kubectl get nodes
terraform -chdir=infra/aws/live/dev output edge_public_ip
```

**Likely causes & fixes**
- **Terraform destroy was run** -> re-apply infra and re-bootstrap ArgoCD before app debugging.

---

### 3.3 Scheduling / Taints / Tolerations

**Symptoms**: pods stuck `Pending`, `0/1 nodes available`, taint errors.

**Checks**
```bash
kubectl -n <ns> describe pod <pod>
kubectl get nodes -o custom-columns=NAME:.metadata.name,TAINTS:.spec.taints
```

**Fix**
- Add matching tolerations to the workload.
- Ensure node selectors/affinity match available nodes.

---

### 3.4 CoreDNS Pending / ArgoCD Sync Unknown

**Symptoms**: `coredns` stuck `Pending`, `cloudradar-platform` shows `Sync=Unknown` with repo-server DNS errors, ESO apps/CRDs never created.

**Checks**
```bash
kubectl get nodes -o wide
kubectl -n kube-system get pods -l k8s-app=kube-dns -o wide
kubectl -n kube-system describe pod -l k8s-app=kube-dns | tail -n 50
kubectl -n argocd describe app cloudradar-platform | tail -n 50
```

**Likely causes**
- Worker did not join the cluster (only control-plane node is Ready).
- Control-plane taint blocks CoreDNS from scheduling.

**Fix**
- Ensure the worker points to the current server IP (`K3S_URL`) and valid token, then restart `k3s-agent`.
- Use the official control-plane taint `node-role.kubernetes.io/control-plane:NoSchedule` and align tolerations for ArgoCD/ESO.
- If you must recover quickly, patch CoreDNS with a matching toleration and restart the deployment.

---

### 3.5 Break-Glass: Serial Console access when SSM is offline

**Use only for emergency diagnostics.**

**Goal**
- Temporarily enable local password login for `ec2-user` on the k3s server via cloud-init.

**Procedure**
```bash
# 1) Generate a SHA-512 password hash locally
openssl passwd -6 'change-me'

# 2) Export it locally (do not commit)
export TF_VAR_k3s_server_serial_console_password_hash="$6$..."
```

3. Apply Terraform with a replace of the k3s server instance so cloud-init re-runs.
4. Perform diagnostics using EC2 Serial Console.
5. Remove the variable and replace the instance again to revert.

**Note**
- This keeps SSH password auth disabled while enabling temporary Serial Console access.

---

## 4) Networking & Traffic

### 4.1 Networking / DNS / CNI

**Symptoms**: service unreachable, DNS failures, timeouts.

**Checks**
```bash
kubectl -n kube-system get pods -l k8s-app=kube-dns -o wide
kubectl -n kube-system logs -l k8s-app=kube-dns --tail=100
kubectl get svc -A | head

# From a debug pod
kubectl -n default run nettest --rm -it --image=curlimages/curl:8.9.1 --restart=Never -- sh
nslookup kubernetes.default
curl -I http://<service>.<ns>.svc.cluster.local
```

**Fixes**
- Restart coredns if failing.
- Check Node security groups, NACLs, and route tables.
- Confirm NAT/VPC endpoints when egress is required (GitHub, SSM, Helm repos).

---

### 4.2 Edge / SG / NodePort / Traefik (502 / 504 / timeouts)

**Symptoms**: Edge 502, service OK internally.

**Checks**
```bash
# Traefik entrypoints
kubectl -n kube-system get svc -l app.kubernetes.io/name=traefik -o wide
kubectl get ingress -A

# From edge instance
curl -I http://<k3s-node-private-ip>:<traefik-nodeport>
curl -I -H "Host: grafana.cloudradar.local" http://<k3s-node-private-ip>:<traefik-nodeport>

# Edge config
sudo cat /etc/nginx/conf.d/edge.conf | sed -n '1,140p'
```

**Root causes**
- Edge SG missing inbound to NodePort
- Edge upstream points to wrong port (e.g., 80 instead of Traefik NodePort)
- Ingress host mismatch
- Grafana subpath loop (edge strips `/grafana` or missing `X-Forwarded-Prefix`)

**Fix**
- Align `edge_grafana_nodeport` / `edge_prometheus_nodeport` with Traefik NodePort
- Ensure SG rule from edge -> k3s NodePort
- Validate Traefik NodePort (k3s default) and update edge upstreams
- For Grafana subpath, keep the `/grafana` prefix and send `X-Forwarded-Prefix: /grafana`

---

## 5) GitOps & Supply Chain

### 5.1 ArgoCD / GitOps Sync

**Symptoms**: App `OutOfSync`, `ComparisonError`, `SyncError`.

**Checks**
```bash
kubectl -n argocd describe application <app>
kubectl -n argocd get events --sort-by=.lastTimestamp | tail -n 50
kubectl -n argocd logs -l app.kubernetes.io/name=argocd-repo-server --tail=200
```

**Common causes**
- Chart repo moved / version missing (helm repo migration)
- Repo auth issues
- Helm template errors

**Fix**
- Update `repoURL` / `targetRevision`
- Fix chart values or kustomize path

---

### 5.2 Prometheus CRDs Missing (kube-prometheus-stack)

**Symptoms**: ArgoCD `SyncFailed` on `Prometheus`/`Alertmanager` resources, `/prometheus` returns `503`, CRDs absent.

**Checks**
```bash
# ArgoCD sync failures
kubectl -n argocd get application prometheus -o jsonpath='{.status.operationState.syncResult.resources[*].kind}{"\n"}' | tr ' ' '\n' | sort -u

# CRDs expected by Prometheus Operator
kubectl get crd | grep -E 'prometheuses|alertmanagers|prometheusagents|thanosrulers'
```

**Root causes**
- CRDs not installed (or not upgraded) by the chart
- ArgoCD sync applied CRs before CRDs existed

**Fix**
- Apply Prometheus CRDs before ArgoCD using server-side apply:
  ```bash
  PROMETHEUS_CRD_TIMEOUT=300s \
  scripts/bootstrap-prometheus-crds.sh <instance-id> us-east-1
  ```
- Re-sync the Prometheus application after CRDs exist.

---

### 5.3 Image Build / GHCR / CI Permissions

**Symptoms**: CI build fails to push images, pulls denied.

**Checks**
```bash
gh run view <run-id>
cat .github/workflows/build-and-push.yml
```

**Common causes**
- `denied: permission_denied: write_package` -> token lacks `write:packages`.
- Package not repo-scoped or access not granted to Actions.
- Image name contains uppercase (invalid for GHCR).

**Fix**
- Ensure repo-scoped GHCR path and lowercase image name.
- Verify Actions permissions and package access.

---

## 6) Workloads & Runtime

### 6.1 Workloads (CrashLoop / Pending / ImagePull)

**Checks**
```bash
kubectl -n <ns> get pods -o wide
kubectl -n <ns> describe pod <pod>
kubectl -n <ns> logs <pod> --tail=200
```

**Common causes**
- `ImagePullBackOff` -> wrong image name/tag, missing GHCR permissions
- `InvalidImageName` -> uppercase in image path (GHCR requires lowercase)
- `CrashLoopBackOff` -> app config error, bad env, missing secret
- `CreateContainerConfigError` -> missing secret/key
- `TLS handshake error: client sent an HTTP request to an HTTPS server` -> app forced to HTTPS while probes/Ingress are HTTP

**Fix**
- Update image tag
- Validate secrets
- Verify env vars
- For Grafana behind Traefik, keep app on HTTP and use HTTPS only at edge

---

### 6.2 Probes / Readiness / Liveness

**Symptoms**: Pod restarts, readiness flaps.

**Checks**
```bash
kubectl -n <ns> describe pod <pod>
```

**Fix**
- Use `/readyz` for readiness, `/healthz` for liveness
- Increase `initialDelaySeconds` for cold starts

---

### 6.3 OOM / Evictions / Resource Pressure

**Symptoms**: pod restarts with `OOMKilled`, node pressure events.

**Checks**
```bash
kubectl -n <ns> describe pod <pod>
kubectl get events -A --sort-by=.lastTimestamp | tail -n 50
kubectl describe node <node> | sed -n '/Conditions/,$p'
```

**Fix**
- Increase memory limits or reduce concurrency.
- Verify node size and eviction thresholds.

---

### 6.4 External Secrets (ESO)

**Symptoms**: missing secrets, pods fail with missing keys.

**Checks**
```bash
kubectl get externalsecret -A
kubectl get secretstore,clustersecretstore -A
kubectl -n <ns> get secret <secret>
```

**Fix**
- Ensure ExternalSecret in correct namespace.
- Ensure target secret name matches app expectation.
- Confirm SSM parameter path and permissions.

---

### 6.5 Init Containers (Init:CrashLoopBackOff)

**Symptoms**: pod stuck in init, main container never starts.

**Checks**
```bash
kubectl -n <ns> describe pod <pod>
kubectl -n <ns> logs <pod> -c <init-container> --tail=200
kubectl -n <ns> logs <pod> -c <init-container> --previous --tail=200
```

**Fix**
- Validate URLs and credentials used by the init script.
- Confirm DNS/egress from cluster to external endpoints.

---

## 7) Storage

### 7.1 Storage (PVC Pending / Mount Failed)

**Checks**
```bash
kubectl get pvc -A
kubectl describe pvc <pvc>
kubectl -n kube-system get pods -l app.kubernetes.io/name=aws-ebs-csi-driver
```

**Fix**
- Check node affinity / taints
- Verify StorageClass
- Ensure the claim `storageClassName` exists (e.g., `ebs-gp3`).
- Align Helm values with the live StorageClass name if mismatched.

---

## 7.2 Prometheus / Observability Incidents (Operational)

Focus on recurring failure modes we have seen in CloudRadar builds.

**A) CRDs missing / Prometheus app Degraded**
**Symptoms**: Prometheus app `Degraded`, `/prometheus` returns 503, no Prometheus/Alertmanager CRs.
**Checks**
```bash
kubectl get crd | grep -E 'prometheuses|alertmanagers|prometheusagents|thanosrulers|scrapeconfigs'
kubectl -n argocd get application prometheus -o yaml | grep -nE 'sync|health|message|reason|status'
```
**Fix**
- Apply Prometheus CRDs before ArgoCD sync (server-side apply).
- Keep `ServerSideApply=true` on the Prometheus app to avoid annotation size failures.

**B) StorageClass mismatch (gp3 vs ebs-gp3)**
**Symptoms**: Prometheus CR `Reconciled=False` with `storage class "gp3" does not exist`, StatefulSet missing.
**Checks**
```bash
kubectl -n monitoring describe prometheus prometheus-kube-prometheus-prometheus | grep -n "storage class"
kubectl get storageclass
```
**Fix**
- Align `storageClassName` in Prometheus values with the live StorageClass (`ebs-gp3`).

**C) Server-side apply / annotation limits**
**Symptoms**: CRD apply fails with `metadata.annotations: Too long`, sync errors on CRDs.
**Checks**
```bash
kubectl -n argocd get application prometheus -o yaml | grep -nE 'ComparisonError|SyncFailed'
```
**Fix**
- Use `kubectl apply --server-side --force-conflicts --validate=false` when applying CRDs.
- In GitOps, enable `ServerSideApply=true` for the Prometheus app.

---

## 8) Utilities

### 8.1 Useful One-Liners

```bash
# Nodes + taints
kubectl get nodes -o custom-columns=NAME:.metadata.name,TAINTS:.spec.taints

# Events (recent)
kubectl get events -A --sort-by=.lastTimestamp | tail -n 50

# Pending pods
kubectl get pods -A --field-selector=status.phase=Pending

# Image pull failures
kubectl get pods -A | awk '$4 ~ /ImagePullBackOff|ErrImagePull/ {print}'
```

---

## 9) When to Escalate

Escalate if:
- The issue is **data loss / security**
- Multiple services are degraded
- Infra changes are required (SG, routing, certs)
