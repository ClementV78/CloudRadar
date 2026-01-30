# Troubleshooting Journal

This log tracks incidents and fixes in reverse chronological order. Use it for debugging patterns and onboarding.

## 2026-01-30

### [gitops/argocd] External Secrets sync failed (CRD missing)
- **Severity:** High
- **Impact:** ESO Application stuck in retry; SecretStore rejected (CRD absent); no secrets synced or pods deployed.
- **Analysis:** Single Kustomize rendered both ESO Helm chart and SecretStore. ArgoCD dry-run validated SecretStore before CRDs existed.
- **Resolution:** Split into two ArgoCD Applications with sync waves: operator (wave 0) installs CRDs; config (wave 1, SkipDryRun) applies SecretStore + ExternalSecrets from `k8s/apps/external-secrets/`. (Refs: issue #191, PR #192)

### [gitops/argocd] SecretStore rejected (serviceAccountRef namespace)
- **Severity:** Medium
- **Impact:** SecretStore webhook denied apply; ExternalSecrets degraded; no secrets synced.
- **Analysis:** SecretStore (namespaced) referenced a service account in `external-secrets`. ESO webhook requires serviceAccountRef namespace to match the SecretStore namespace.
- **Resolution:** Switch to `ClusterSecretStore` and set ExternalSecrets to `secretStoreRef.kind=ClusterSecretStore`. (Refs: issue #195, PR #196)

### [gitops/argocd] Sync blocked when CLI not available locally
- **Severity:** Low
- **Impact:** Operators unable to sync ArgoCD apps during bootstrap.
- **Analysis:** `argocd` CLI not installed locally; TLS prompt in non-interactive shell caused EOF.
- **Resolution:** Use the ArgoCD CLI inside the `argocd-server` pod with `--grpc-web` and `--insecure`. Documented in `docs/runbooks/bootstrap/argocd-bootstrap.md`. (Refs: issue #195, PR #196)

## 2026-01-26

### [gitops/argocd] App sync failed due to malformed processor manifest
- **Severity:** Medium
- **Impact:** ArgoCD application stayed in `sync=Unknown`, auto-sync was skipped, and bootstrap wait steps failed.
- **Analysis:** `kustomize build k8s/apps` failed because processor env values contained unquoted `:` which broke YAML parsing.
- **Resolution:** Quoted Redis key values in `k8s/apps/processor/deployment.yaml`. (Refs: PR #168)

### [app/k8s] Processor crash loop due to aggressive probes
- **Severity:** Medium
- **Impact:** `processor` pod restarted and never became Ready.
- **Analysis:** Readiness/liveness probes fired immediately while Spring Boot was still starting (~30s).
- **Resolution:** Added `startupProbe` and initial delays for readiness/liveness. (Refs: PR #169)

### [app/k8s] Admin-scale image pull blocked (GHCR private)
- **Severity:** Medium
- **Impact:** `admin-scale` pod stuck in `ImagePullBackOff` (`403/401 Unauthorized`) and API unavailable.
- **Analysis:** GHCR package `cloudradar-admin-scale` was private; k3s tried anonymous pull.
- **Resolution:** Made GHCR image public (or configure imagePullSecret). Pod started successfully afterward. (Refs: PR #160)

### [edge/k8s] Admin NodePort unreachable from edge
- **Severity:** Medium
- **Impact:** Edge returned `504 Gateway Time-out` when calling the admin-scale API.
- **Analysis:** k3s security group lacked inbound rule for the admin NodePort (`32737/TCP`).
- **Resolution:** Added SG rule for the NodePort via Terraform and redeployed. (Refs: issue #161, PR #162)

### [gitops/argocd] Ingester replica scaling reverted
- **Severity:** Medium
- **Impact:** Manual scale via admin API reverted by ArgoCD sync.
- **Analysis:** ArgoCD managed the `spec.replicas` field and reconciled it back to the desired state from Git.
- **Resolution:** Added ArgoCD `ignoreDifferences` for `spec.replicas` on the ingester Deployment and documented it in the bootstrap runbook. (Refs: issue #163, PR #164)

## 2026-01-25

### [infra/k3s] Worker node NotReady
- **Severity:** High
- **Impact:** k3s worker `ip-10-0-101-106` stopped posting status; ArgoCD sync stalled; services lacked endpoints.
- **Analysis:** `kubectl describe node` showed `NodeStatusUnknown` and `kubelet stopped posting node status`. System metrics showed RAM exhaustion (≈1.9 GB total, ~64 MB free) and high IO wait. SSM commands delayed.
- **Resolution:** Added 2 GB swap on control-plane and new worker to stabilize memory. Replaced failing worker (ASG created a new Ready node). Cleaned up the NotReady node. Implemented cloud-init swap provisioning for k3s server and agents in PR #152.

### [app/opensky] AWS IPs blocked by OpenSky
- **Severity:** Medium
- **Impact:** Ingestion failed with `ConnectException`, no events pushed to Redis.
- **Analysis:** Logs showed token acquisition failing. Direct curl from AWS timed out. Confirmed OpenSky blocks hyperscaler IPs. Tested Cloudflare Worker proxy to shift egress IP to Cloudflare.
- **Resolution:** Added SSM-configured OpenSky base/token URLs read by the ingester (PR #151). Stored Worker URLs in SSM and verified ingestion succeeded (events fetched/pushed). Reduced bbox to ~200x200 km to stay under 25 square degrees (PR #153).

### [ci/infra] Edge nginx check failures after boot
- **Severity:** Medium
- **Impact:** CI smoke tests failed because nginx was inactive after reboot.
- **Analysis:** Cloud-init failed to fetch the Basic Auth password from SSM due to early SSM connectivity timeout. Nginx never started.
- **Resolution:** Added SSM retry/backoff in edge user-data and enhanced CI diagnostics (systemctl + journal) in PR #146. Verified CI run 21334127283.

## Template

### [category/subcategory] Short summary
- **Severity:** Low | Medium | High
- **Impact:**
- **Analysis:**
- **Resolution:**
