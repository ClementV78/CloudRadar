# Troubleshooting Journal

This log tracks incidents and fixes in reverse chronological order. Use it for debugging patterns and onboarding.

## 2026-02-03

### [infra/edge] Edge 502 after rebuild (Traefik NodePort drift)
- **Severity:** High
- **Impact:** `/grafana` and `/prometheus` returned 502 from the edge even though monitoring pods were healthy.
- **Analysis:** After a destroy/recreate, Traefik NodePorts changed (e.g., web `30382`), while Terraform edge config still targeted the old NodePort (`30353`), so Nginx proxied to a closed port.
- **Resolution:** Pin Traefik NodePorts via k3s HelmChartConfig (web `30080`, websecure `30443`) and align edge `edge_grafana_nodeport` / `edge_prometheus_nodeport` to `30080`. (Refs: issue #286)

### [obs/monitoring] Prometheus sync failed (annotations too long)
- **Severity:** High
- **Impact:** Prometheus app stayed `Degraded`; ArgoCD sync failed with ConfigMap annotation size errors.
- **Analysis:** `prometheus-crds-upgrade` ConfigMap is large; client-side apply adds `last-applied-configuration` annotation exceeding 256KiB.
- **Resolution:** Enable ArgoCD Server-Side Apply for the Prometheus application.

### [ci/registry] ImagePullBackOff (GHCR tags missing)
- **Severity:** High
- **Impact:** app pods stayed Pending/BackOff because images under `ghcr.io/clementv78/cloudradar/*` were missing.
- **Analysis:** build workflow pushed images using the mixed-case repo name; manifests reference lowercase GHCR paths, so tags were not found.
- **Resolution:** Update build-and-push workflow to use lowercase repo and rebuild/push images.

### [app/k8s] Pods fail with InvalidImageName (GHCR uppercase)
- **Severity:** High
- **Impact:** `admin-scale`, `healthz`, and `processor` pods failed to start; app health stayed `Progressing`.
- **Analysis:** GHCR repository name in manifests used `CloudRadar` (uppercase), which is invalid for image references.
- **Resolution:** Update all GHCR image references to lowercase `ghcr.io/clementv78/cloudradar/...`.

### [ci/infra] CRD wait failed (NotFound before creation)
- **Severity:** Medium
- **Impact:** `argocd-platform` job failed while waiting for ESO CRDs; bootstrap blocked.
- **Analysis:** `kubectl wait crd/<name>` fails if the CRD does not exist yet. The platform app was created but CRDs had not been registered.
- **Resolution:** Poll for CRD existence first, then wait for `Established`, with a 5 min total timeout.

### [ci/infra] argocd-platform job failed (unbound variable)
- **Severity:** Medium
- **Impact:** `ci-infra` failed during `argocd-platform` with `crd: unbound variable`, blocking bootstrap flow.
- **Analysis:** `bootstrap-argocd-app.sh` interpolated `${crd}` locally with `set -u`, so the SSM command failed before execution.
- **Resolution:** Escape `$crd` in the SSM command so it evaluates on the instance.

### [gitops/argocd] Root app sync failed (ESO CRDs missing)
- **Severity:** High
- **Impact:** `cloudradar` Application sync failed with `SyncError`; namespaces/apps were not created.
- **Investigation (timeline):**
  - `kubectl -n argocd describe app cloudradar` showed `ExternalSecret`/`ClusterSecretStore` resources invalid.
  - `kubectl get crd | grep -i external-secrets` returned empty.
  - ESO Applications were not present, so CRDs never installed.
- **Analysis:** ArgoCD root app synced `k8s/apps` before `k8s/platform`, so External Secrets resources applied before ESO CRDs existed.
- **Resolution:** Split bootstrap into platform-first then apps, create separate root apps (`cloudradar-platform`, `cloudradar`), and remove `external-secrets` from `k8s/apps/kustomization.yaml`.

## 2026-02-02

### [obs/monitoring] Prometheus stuck OutOfSync (missing CRDs)
- **Severity:** High
- **Impact:** `/prometheus` returned `503` and ArgoCD stayed `OutOfSync/Progressing`; no Prometheus/Alertmanager CRs applied.
- **Investigation (timeline):**
  - ArgoCD sync results showed `SyncFailed` for `Prometheus`/`Alertmanager` CRs.
  - `kubectl get crd | grep -E 'prometheuses|alertmanagers|prometheusagents|thanosrulers'` returned empty.
  - Node exporter and operator resources were present, but core CRDs were missing.
- **Analysis:** Prometheus Operator CRDs were not installed/updated, so ArgoCD could not apply `Prometheus` and `Alertmanager` resources.
- **Resolution:** Upgrade to `kube-prometheus-stack` `81.4.2` and enable CRD install/upgrade in chart values (`crds.enabled=true`, `crds.upgradeJob.enabled=true`), then re-sync the app.

### [obs/edge] Grafana redirect loop (subpath + HTTPS mismatch)
- **Severity:** High
- **Impact:** `/grafana` returned `ERR_TOO_MANY_REDIRECTS` and edge showed 502; Grafana pods failed readiness due to HTTP/HTTPS mismatch.
- **Investigation (timeline):**
  - Edge returned `301` from `/grafana/login` to itself.
  - Grafana logs showed `TLS handshake error: client sent an HTTP request to an HTTPS server`.
  - Readiness/Liveness probes failed with 400 and connection refused.
- **Analysis:** Edge stripped `/grafana` by using `proxy_pass .../` and didn’t send `X-Forwarded-Prefix`, so Grafana redirected back to `/grafana` indefinitely. Grafana was also forced to HTTPS while Traefik/probes use HTTP.
- **Resolution:** Keep `/grafana` prefix in the edge proxy (`proxy_pass` without trailing slash) and add `X-Forwarded-Prefix /grafana`, `X-Forwarded-Host`, `X-Forwarded-Proto https`. Set Grafana to HTTP internally while keeping external `root_url` HTTPS.

### [infra/edge] Edge 502 after control-plane taint (Traefik NodePort target mismatch)
- **Severity:** High
- **Impact:** Edge `/grafana` and `/prometheus` returned 502 even though services were healthy in-cluster.
- **Investigation (timeline):**
  - Verified Grafana/Prometheus pods were Running and Services had endpoints.
  - Confirmed Traefik Service exposes NodePorts and Ingresses were present.
  - Edge Nginx upstreams still pointed to `control-plane:80`.
- **Analysis:** This setup previously worked because Grafana ran on the control-plane node. After adding a control-plane taint, Grafana moved to the worker while Traefik continued serving on a NodePort. Edge upstreams still targeted the control-plane on port 80, so traffic never reached Traefik/Ingress.
- **Resolution:** Route edge upstreams to the Traefik NodePort on a k3s node and open the NodePort in the k3s worker SG. Avoid hardcoding control-plane IP for Ingress traffic; use the NodePort target instead.

### [obs/monitoring] Grafana chart repo migration (old chart version missing)
- **Severity:** Medium
- **Impact:** ArgoCD reported `ComparisonError` and failed to generate manifests for Grafana; app remained `Unknown` and sync stalled.
- **Investigation (timeline):**
  - `kubectl -n argocd describe application grafana` showed manifest generation failures.
  - `argocd-repo-server` logs showed `helm pull ... grafana` failing for version `7.6.10` in `https://grafana.github.io/helm-charts`.
- **Analysis:** The Grafana Helm chart moved to `grafana-community/helm-charts`. The old repo no longer served the pinned version.
- **Resolution:** Switch `repoURL` to `https://grafana-community.github.io/helm-charts` and update `targetRevision` to a valid release (e.g., `10.5.15`).

## 2026-02-01

### [app/health] healthz CrashLoop blocks ArgoCD sync (probes coupled to cluster metrics)
- **Severity:** Medium
- **Impact:** `cloudradar` Application stayed `Progressing`; bootstrap/smoke-test waits failed; edge `/healthz` returned 503 while the pod restarted.
- **Investigation (timeline):**
  - Observed `healthz` in CrashLoopBackOff and ArgoCD waiting for `apps/Deployment/healthz` (`kubectl -n cloudradar get pods`, `kubectl -n argocd get app cloudradar -o yaml`).
  - `kubectl describe deployment/healthz` showed liveness/readiness failures on `/healthz` with timeouts/refused connections.
  - `healthz` logs showed BrokenPipe during early probes; `metrics-server` initially reported “no metrics to serve” before stabilizing.
- **Analysis:** `/healthz` is a cluster status endpoint (API + metrics). Using it for liveness/readiness made the pod fail during cluster warmup, which blocked ArgoCD sync.
- **Resolution:** Add `/readyz` endpoint for probes and update the `healthz` Deployment to use `/readyz` with relaxed timeouts.

## 2026-01-31

### [gitops/argocd] Repo-server CrashLoop (default probes/resources too tight)
- **Severity:** Medium
- **Impact:** ArgoCD app status stuck `Unknown/Progressing`; repo-server endpoint had no ready pods; GitOps sync flapped.
- **Investigation (timeline):**
  - Noticed `argocd-repo-server` liveness/readiness failing and CrashLooping (`kubectl describe pod`, `kubectl get pods`).
  - Verified Service had no endpoints for repo-server and app sync showed repo-server connection refused (`kubectl get svc/endpoints`, `kubectl get app`).
  - Checked chart defaults and live Deployment: repo-server `resources: {}` and probes with `timeoutSeconds: 1` (`helm show values`, `kubectl get deploy -o yaml`).
- **Analysis:** Default repo-server resources and 1s probe timeouts are too aggressive for this environment, causing repeated liveness/readiness failures under load.
- **Resolution:** Add explicit repo-server requests/limits and relax probe timeouts in the ArgoCD bootstrap values file; bootstrap uses that file on install/upgrade. (Refs: issue #223)

### [infra/edge] Terraform apply failed (duplicate SG rule for port 80)
- **Severity:** Medium
- **Impact:** `ci-infra`/local apply failed while updating edge routing for Grafana/Prometheus on port 80.
- **Analysis:** `k3s_nodeports_from_edge` generated an ingress rule for TCP/80 while `k3s_ingress_from_edge` already allows TCP/80 from the edge SG, resulting in `InvalidPermission.Duplicate`.
- **Resolution:** Filter port 80/443 out of the NodePort rule set so only the explicit ingress rules handle those ports. (Refs: issue #219)

### [k3s/storage] Redis Pending (local-path PV node affinity + taints)
- **Severity:** High
- **Impact:** `redis-0` stayed Pending; `processor` and `healthz` CrashLooped due to missing Redis; ArgoCD app remained `Unknown/Progressing`.
- **Investigation (timeline):**
  - Context: the worker node was terminated/replaced to change its instance type, which orphaned node-local storage.
  - Observed `healthz`/`processor` CrashLoop with probe failures and Redis connection errors (`kubectl logs`, `kubectl describe pod`).
  - Checked `redis-0` status → `Pending` with no endpoints; confirmed Service existed but had no pods (`kubectl get pods,svc`, `kubectl get endpoints`).
  - `describe pod redis-0` showed scheduling failures: *node affinity mismatch* + *untolerated taint* (`kubectl describe pod`).
  - `get pvc` showed Redis PVC bound to a PV using `local-path`, which is **node-local** (`kubectl get pvc`).
  - Confirmed the bound PV was tied to the **old NotReady worker** (`kubectl get nodes -o wide`), so the new worker could not mount it.
- **Analysis:** Redis PVC uses `local-path`, so the PV was bound to the old NotReady worker. The new worker could not satisfy PV node affinity, and the control-plane was tainted (no toleration), leaving no eligible node to schedule Redis.
- **Resolution:** Delete the NotReady node from the cluster or recreate the Redis PVC to rebind on the healthy worker. Long term, move Redis to a networked storage class (e.g., `ebs-gp3`) to avoid node affinity lock-in.

### [obs/monitoring] Grafana/Prometheus missing (Application namespace overridden)
- **Severity:** Medium
- **Impact:** Monitoring namespace had no Grafana/Prometheus services or pods; edge `/grafana` and `/prometheus` returned 502.
- **Analysis:** `k8s/apps/monitoring/kustomization.yaml` set `namespace: monitoring`, which forced ArgoCD Application CRs into the monitoring namespace. ArgoCD only watches Applications in the argocd namespace, so the apps were never created.
- **Resolution:** Remove the namespace transformer from the monitoring kustomization and keep namespaces explicitly on non-Application resources. Align edge routing with Traefik host-based Ingress by rewriting Host headers and add a Prometheus Ingress for parity. (Refs: issue #214)

### [ci/infra] SSM SendCommand intermittently fails (InvalidInstanceId)
- **Severity:** Low
- **Impact:** `ci-infra` k3s-ready-check failed once with `InvalidInstanceId`; rerun succeeded.
- **Analysis:** SSM SendCommand can be issued before the instance transitions to a valid/managed state. A second failure mode was identified later: a `send_ssm_command` log line printed to stdout, which polluted the returned CommandId and caused `ValidationException` on `GetCommandInvocation`.
- **Resolution:** Add preflight SSM PingStatus check and retry SendCommand with backoff in ci-infra workflow. Route all send-command logs to stderr and validate the CommandId against UUID format before polling. (Refs: issue #205, issue #210)

### [gitops/argocd] Bootstrap failed (Helm nodeSelector parsed as string)
- **Severity:** Medium
- **Impact:** ArgoCD bootstrap job failed; GitOps root Application not created.
- **Analysis:** Helm `--set` with dotted key for nodeSelector was parsed as a string, causing `json: cannot unmarshal object into Go struct field PodSpec.spec.template.spec.nodeSelector of type string`.
- **Resolution:** Switch bootstrap to `--set-json` for `global.nodeSelector` and `global.tolerations`. (Refs: issue #206)

## 2026-01-30

### [infra/k3s] Control-plane taint missing → workloads scheduled on server (OOM/SSM black screen)
- **Severity:** High
- **Impact:** App pods (Java) and Redis scheduled on the k3s server; repeated OOM kills; SSM Session Manager showed a black screen; cluster became unstable.
- **Analysis:** The control-plane node had **no taints** (`Taints: <none>`). The taint was applied manually previously and **not codified** in IaC. After infra rebuild, the new server booted without a taint, so regular workloads were eligible to run on it.
- **Resolution:** Re-applied taint `dedicated=control-plane:NoSchedule` and deleted app/redis/ESO pods so they rescheduled to the worker. **Follow-up:** codify the taint in k3s server install (e.g., `K3S_NODE_TAINT` / `--node-taint`) and document the expected taints. (Refs: issue #203)

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

### [gitops/argocd] ClusterSecretStore InvalidProviderConfig (IRSA on k3s)
- **Severity:** Medium
- **Impact:** ClusterSecretStore oscillated to `InvalidProviderConfig`; ExternalSecrets synced inconsistently.
- **Analysis:** `auth.jwt.serviceAccountRef` is IRSA (EKS) specific. k3s on EC2 must use instance profile credentials instead.
- **Resolution:** Remove `auth.jwt` from the ClusterSecretStore so ESO uses EC2 instance profile credentials. (Refs: issue #197, PR TBD)

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
