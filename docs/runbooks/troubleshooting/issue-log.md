# Troubleshooting Journal

This log tracks incidents and fixes in reverse chronological order. Use it for debugging patterns and onboarding.

## 2026-02-14

### [frontend/map] Marker disappears on click + no visual typing across aircraft categories
- **Severity:** Medium
- **Impact:** Clicking a marker could immediately close the selected aircraft context; map readability was degraded because all markers looked identical.
- **Signal:** On the Leaflet UI, selected aircraft sometimes disappeared after click during refresh cycles; helicopters/military/private/size profiles were not visually distinguishable.
- **Analysis:** Selection state was cleared too aggressively when a selected `icao24` was temporarily missing in one poll cycle. Marker rendering used a single SVG style for all flights.
- **Resolution:**
  1. Add selection stability guard: clear selected aircraft only after 3 consecutive missing cycles.
  2. Enrich `/api/flights` map payload with metadata used by UI rendering (`militaryHint`, `airframeType`, `fleetType`, `aircraftSize`).
  3. Render typed markers in frontend (helicopter vs airplane glyph, color by fleet/military hint, size by aircraft profile).
- **Guardrail:** Keep map symbolization tied to backend-derived low-cardinality metadata to avoid UI-only heuristics drift.
- **Refs:** issue #433

### [dashboard/api] Map typing fields mostly `unknown` despite aircraft DB being available
- **Severity:** Medium
- **Impact:** UI markers remained visually uniform because `/api/flights` typing fields (`airframeType`, `fleetType`, `aircraftSize`) were often `unknown`.
- **Signal:** `processor` pod had aircraft DB downloaded and mounted, but Redis `cloudradar:aircraft:last` entries contained only OpenSky telemetry; `/api/flights` returned mostly `unknown` typing values.
- **Analysis:** `FlightQueryService.listFlights` loaded snapshots with metadata disabled by default (enabled only for metadata-driven filters). This made map typing implicitly depend on metadata persistence in Redis.
- **Resolution:** Force on-the-fly metadata enrichment in `listFlights` read path (`loadSnapshots(..., includeMetadata=true, includeOwnerOperator=false)`), and add unit coverage to prevent regressions.
- **Guardrail:** Keep map typing enrichment as a dashboard read responsibility; Redis remains telemetry-first cache.
- **Refs:** issue #435

### [edge/ui] Repeated Basic Auth popup loop on dashboard refresh + duplicate/stale map markers
- **Severity:** High
- **Impact:** Public UI became hard to use: browser auth modal reappeared every refresh cycle, and map could show duplicate/stale aircraft markers.
- **Signal:** At `https://cloudradar.iotx.fr`, login popup reopened repeatedly while frontend polled map APIs; aircraft list did not consistently match the latest OpenSky fetch set.
- **Analysis:** Edge Nginx enforced Basic Auth globally, including `/api/*`, so any unauthenticated periodic call triggered browser re-auth.  
  On data path, dashboard snapshots were not explicitly constrained to the latest processed OpenSky batch, so stale/historical entries could still surface.
- **Resolution:**  
  1. Disable edge Basic Auth for UI routes (`/` and `/api/`) while keeping auth on sensitive routes (`/admin`, `/grafana`, `/prometheus`).  
  2. Add `opensky_fetch_epoch` in ingester events (same marker per OpenSky poll cycle).  
  3. Filter dashboard `GET /api/flights` to aircraft from the latest `opensky_fetch_epoch` batch only; expose `latestOpenSkyBatchEpoch` in response.  
  4. Frontend refresh loop now reacts to batch changes (with frequent polling and batch-aware KPI/detail refresh).
- **Guardrail:** For map consistency, treat latest OpenSky batch as source-of-truth snapshot boundary; avoid mixing events across batches in flight list responses.
- **Refs:** issue #431, PR #432

## 2026-02-12

### [ci/eso] processor crashed after bootstrap (ESO ready but app secret not fully synced yet)
- **Severity:** High
- **Impact:** `processor` entered `CrashLoopBackOff`; ArgoCD app `cloudradar` stayed `Degraded`; `smoke-tests` failed on post-apply checks.
- **Signal:** `Aircraft DB enrichment disabled; skipping aircraft DB download` in initContainer logs, then main container failed with `Aircraft DB not found at /refdata/aircraft.db`.
- **Analysis:** `eso-ready-check` validates ESO controller/CRDs, but did not guarantee `externalsecret/processor-aircraft-db` was already `Ready=True` with materialized Secret keys when `processor` pod started.
- **Resolution:** Add a dedicated CI gate `eso-secrets-ready` after `argocd-apps` to wait for all `ExternalSecret` resources (`Ready=True`) and verify each target Secret is materialized before restore/smoke.
  Also harden processor deployment: required secret keys (`optional: false`) and fail-fast init logic when enrichment is enabled but `s3-uri` is invalid.
- **Guardrail:** Do not rely on ESO pod readiness alone for app startup ordering; gate on application-level `ExternalSecret` readiness and target Secret materialization.
- **Refs:** issue #404

### [gitops/argocd] ingester runtime replicas reset to manifest value on sync
- **Severity:** Medium
- **Impact:** Ingestion stopped unexpectedly after bootstrap/sync because `ingester` replicas could return to `0`.
- **Signal:** `ingester` had been manually/runtime scaled to `>=1`, then dropped back to `0` after app sync/redeploy.
- **Analysis:** `ignoreDifferences` was set for `Deployment/ingester` `/spec/replicas`, but sync behavior may still re-apply manifest fields unless `RespectIgnoreDifferences=true` is included in sync options.
- **Resolution:** In `bootstrap-argocd-app.sh`, when `IGNORE_INGESTER_REPLICAS=true`, also set `syncOptions: RespectIgnoreDifferences=true` with the existing replica ignore rule.
- **Guardrail:** Keep runtime scaling use-cases protected by pairing `ignoreDifferences` with `RespectIgnoreDifferences=true`.
- **Refs:** issue #405

## 2026-02-11

### [app/data] processor initContainer failed with S3 403 after aircraft DB enablement
- **Severity:** High
- **Impact:** `processor` rollout blocked (`Init:CrashLoopBackOff`), aircraft metadata enrichment unavailable.
- **Signal:** `fatal error: An error occurred (403) when calling the HeadObject operation: Forbidden` in `aircraft-db-download` initContainer logs.
- **Analysis:** Runtime SSM/ESO values pointed to `s3://cloudradar-dev-aircraft-db/...`, but k3s node IAM policy allowed only the Terraform default bucket `cloudradar-dev-<account>-reference-data/*`. This bucket mismatch caused S3 read denial.
- **Resolution:** In `ci-infra`, pass `aircraft_reference_bucket_name` from GitHub Actions variable (`AIRCRAFT_REFERENCE_BUCKET_NAME`, fallback `TF_AIRCRAFT_REFERENCE_BUCKET_NAME`) during dev plan/apply so IAM policy matches the runtime aircraft DB bucket.
- **Guardrail:** Keep `PROCESSOR_AIRCRAFT_DB_S3_URI` and `aircraft_reference_bucket_name` aligned; verify both SSM and `processor-aircraft-db` Secret before rollout restart.
- **Refs:** issue #392

## 2026-02-09

### [ci/infra] ci-infra-destroy failed (unbound variable in Redis backup step)
- **Severity:** High
- **Impact:** `ci-infra-destroy` failed before destroy, so infra teardown was blocked.
- **Signal:** `/home/runner/...sh: line 64: i: unbound variable` in step `Backup Redis + rotate (dev only)`.
- **Analysis:** The workflow step runs with `set -u` and builds SSM command payloads via `jq --arg`. A `$i` loop inside the payload was expanded on the runner instead of on the instance.
- **Resolution:** Pass the `$i` loop payload via single quotes to prevent runner expansion (SSM executes it on the instance).
- **Refs:** issue #369

## 2026-02-10

### [k8s/app] processor stuck in Init:ImagePullBackOff (invalid aws-cli initContainer image)
- **Severity:** Medium
- **Impact:** `processor` rollout blocked; aircraft reference DB download initContainer never started.
- **Signal:** `Failed to pull image "amazon/aws-cli:2" ... docker.io/amazon/aws-cli:2: not found` and `ImagePullBackOff`.
- **Analysis:** The initContainer image reference used an invalid tag (`:2`) and later an unavailable ECR Public tag. Both resulted in `...: not found` pulls.
- **Resolution:** Pin a concrete Docker Hub AWS CLI tag, e.g. `amazon/aws-cli:<major.minor.patch>`, in `k8s/apps/processor/deployment.yaml`.
- **Refs:** issue #377, PR #378, issue #379

### [ci/k8s] Processor ran an outdated image tag (GHCR tag drift)
- **Severity:** Medium
- **Impact:** Cluster was running `processor:0.1.4` even after code changes were merged, leading to confusion (digest mismatch vs `sha-<commit>` builds).
- **Signal:** K8s `imageID` digest did not match the latest `sha-<commit>` image published for the same code revision.
- **Analysis:** `.github/workflows/build-and-push.yml` publishes `main/latest/sha-*` tags on `push` to `main`, but semver-like tags (e.g. `0.1.4`) are only published on git tags (`v*`). Manifests pinned `:0.1.4`, so the cluster could stay on an older image even while newer images existed.
- **Resolution:** Introduce a repo `VERSION` file and publish `:<VERSION>` tags on `main` pushes; bump manifests to the new version when code changes are merged.
- **Guardrail:** Use `scripts/release/bump-app-version.sh` for a single-command bump+sync, and keep CI check `scripts/ci/check-app-version-sync.sh` enabled in `ci-k8s` so manifest tags cannot drift from `VERSION`.
- **Refs:** issue #381

## 2026-02-08

### [infra/dns] Terraform apply failed creating Route 53 records (record already exists)
- **Severity:** Medium
- **Impact:** `terraform apply` failed when creating `aws_route53_record` for the delegated zone because the record set already existed (state drift / partial destroy).
- **Signal:** `InvalidChangeBatch: Tried to create resource record set ... but it already exists`.
- **Resolution:** Set `allow_overwrite = true` on the Route 53 record resource to upsert instead of failing.
- **Refs:** issue #341, PR #342, issue #317 / PR #344

### [infra/obs] Terraform apply failed creating CloudWatch Log Group (missing IAM permissions)
- **Severity:** High
- **Impact:** VPC Flow Logs provisioning failed because CI role lacked `logs:CreateLogGroup` (and related) permissions.
- **Signal:** `AccessDeniedException: ... is not authorized to perform: logs:CreateLogGroup`.
- **Resolution:** Extend the CI role inline policy created by `scripts/bootstrap-aws.sh` to allow managing `/cloudradar/*` log groups, then re-run the script to update the role policy.
- **Refs:** issue #317 / PR #344

## 2026-02-07

### [infra/dns] Terraform destroy failed (Route53 HostedZoneNotEmpty)
- **Severity:** Medium
- **Impact:** `terraform destroy`/`ci-infra-destroy` could not delete the Route53 hosted zone.
- **Analysis:** The hosted zone contained A records created outside Terraform (ci-infra `dns-sync` job). Terraform did not own those recordsets, so the zone stayed non-empty and deletion failed.
- **Resolution:** Manage Route53 A records and Grafana DNS SSM parameters in Terraform, remove the out-of-band `dns-sync` workflow job, and move the hosted zone out of the env state (managed under `infra/aws/bootstrap`). (Refs: issue #341)

## 2026-02-06

### [obs/monitoring] Traefik dashboard showed "No data" (ServiceMonitor port mismatch)
- **Severity:** Medium
- **Impact:** Traefik Grafana dashboard showed no metrics; Prometheus had no `traefik_*` series.
- **Analysis:** Traefik metrics were enabled on entrypoint `metrics` (`:9100`), but the default `kube-system/traefik` Service exposed only `web`/`websecure` ports (no `metrics` port). The `ServiceMonitor traefik` expected `port: metrics`, so Prometheus could not build scrape targets.
- **Resolution:** Scrape Traefik via `PodMonitor` on port `metrics` and keep a stable `job="traefik"` via `jobLabel`. (Refs: issue #335)

### [ci/infra] argocd-platform job failed (unbound variable in CRD wait)
- **Severity:** Medium
- **Impact:** `ci-infra` failed during `argocd-platform`, blocking GitOps bootstrap on fresh infra rebuilds.
- **Analysis:** `bootstrap-argocd-app.sh` builds an SSM command string under `set -u`. The embedded `awk` program used `$1/$2`, which were expanded locally (and failed) instead of being evaluated on the instance.
- **Resolution:** Escape `\$1`/`\$2` in the SSM command string so the awk fields are evaluated on the instance. (Refs: issue #321)

### [ci/infra] argocd-platform job failed (false negative on CRD Established)
- **Severity:** Medium
- **Impact:** `ci-infra` failed during `argocd-platform` even though the ESO CRD existed.
- **Analysis:** The CRD wait loop parsed CRD YAML and assumed the condition line starts with `type: Established`. In practice, conditions are list items and commonly rendered as `- type: Established`, so the wait loop never detected `Established=True` and timed out.
- **Resolution:** Detect `Established=True` by parsing CRD JSON (`kubectl get crd -o json | tr | grep`) rather than relying on YAML token positions. (Refs: issue #321)

### [gitops/argocd] external-secrets-config OutOfSync (namespace missing)
- **Severity:** Medium
- **Impact:** `external-secrets-config` stayed `OutOfSync` and repeatedly failed to apply `ExternalSecret` resources targeting the `monitoring` namespace.
- **Analysis:** `external-secrets-config` is created during `argocd-platform` (before the root `cloudradar` apps app is bootstrapped). The manifests include `ExternalSecret` objects in `metadata.namespace: monitoring`, but the `monitoring` namespace used to be created later by `k8s/apps/monitoring`. `CreateNamespace=true` only creates the Application destination namespace, not arbitrary namespaces referenced by other resources.
- **Resolution:** Create the `monitoring` namespace as part of `k8s/platform` (cluster baseline) and remove it from `k8s/apps/monitoring` to avoid ArgoCD shared resource ownership/conflicts.

### [gitops/argocd] cloudradar sync failed (oversized dashboards ConfigMap annotation)
- **Severity:** High
- **Impact:** `ci-infra` smoke-tests failed because the ArgoCD `cloudradar` Application could not sync. Grafana stayed `Degraded` because the dashboards ConfigMap was never created.
- **Analysis:** The dashboards ConfigMap (`grafana-dashboards-default`) is generated by kustomize and is large. Client-side apply adds `kubectl.kubernetes.io/last-applied-configuration`, which can exceed the 256KiB annotation limit and makes the ConfigMap invalid.
- **Resolution:** Enable ArgoCD Server-Side Apply for the root `cloudradar` Application so large resources are applied without the last-applied annotation. (Refs: issue #327)

### [gitops/argocd] cloudradar remained OutOfSync (redis StatefulSet spec drift)
- **Severity:** Medium
- **Impact:** `ci-infra` smoke-tests failed because ArgoCD `cloudradar` stayed `OutOfSync` even though overall health was `Healthy`.
- **Analysis:** Only `StatefulSet data/redis` remained `OutOfSync`. The live object included defaulted fields (e.g., `persistentVolumeClaimRetentionPolicy`, `updateStrategy`, and PVC template `apiVersion/kind/volumeMode`) not present in the repo manifest, so ArgoCD continuously re-applied but still detected a diff.
- **Resolution:** Make `k8s/apps/redis/statefulset.yaml` explicit about those fields so desired matches live and ArgoCD can converge to `Synced`. (Refs: issue #330)
## 2026-02-05

### [obs/monitoring] Prometheus degraded (storageclass mismatch)
- **Severity:** High
- **Impact:** Prometheus StatefulSet never created; `/prometheus` smoke test failed after rebuild.
- **Analysis:** Prometheus CR requested `storageClassName: gp3` but the cluster only provided `ebs-gp3`, so reconciliation failed with `storage class "gp3" does not exist`.
- **Resolution:** Align Prometheus chart values to use `storageClassName: ebs-gp3`. (Refs: issue #304)

## 2026-02-04

### [obs/monitoring] Prometheus CRDs missing (server-side apply timeouts)
- **Severity:** High
- **Impact:** `/prometheus` returned `503`; Prometheus pods absent; smoke-tests failed.
- **Analysis:** CRD upgrade job disabled (annotation size issue). After rebuild, core Prometheus CRDs were missing and API timeouts on `t3.small` made manual apply flaky.
- **Resolution:** Version Prometheus CRDs under `k8s/platform/crds/prometheus` and apply them **before ArgoCD** via CI using server-side apply (`--force-conflicts --validate=false --request-timeout=300s`). Increase control-plane size to `t3a.medium` to reduce API timeouts. (Refs: issue #299, #298)

## 2026-02-03

### [infra/k3s] CoreDNS Pending after rebuild (worker not joined)
- **Severity:** High
- **Impact:** DNS unavailable; ArgoCD `cloudradar-platform` stayed `Sync=Unknown` and ESO apps/CRDs never created; bootstrap failed.
- **Analysis:** Worker k3s-agent still pointed to the previous server IP after rebuild, so it never joined. With only the tainted control-plane node available, CoreDNS could not schedule (no matching toleration), which broke DNS and repo-server resolution.
- **Resolution:** Use the official control-plane taint (`node-role.kubernetes.io/control-plane:NoSchedule`) and align tolerations for platform components (ArgoCD/ESO). Ensure worker `K3S_URL` targets the current server IP after rebuild. (Refs: issue #290)

### [infra/edge] Edge 502 after rebuild (Traefik NodePort drift)
- **Severity:** High
- **Impact:** `/grafana` and `/prometheus` returned 502 from the edge even though monitoring pods were healthy.
- **Analysis:** The k3s HelmChartConfig used `service.spec.ports`, which the Traefik chart ignores. NodePort pinning never applied, so NodePorts drifted after rebuilds and the edge still targeted the old port.
- **Resolution:** Fix HelmChartConfig keys to `ports.web.nodePort` and `ports.websecure.nodePort` and keep edge `edge_grafana_nodeport` / `edge_prometheus_nodeport` aligned to `30080`. (Refs: issue #286, #294)

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
- **Resolution:** Keep `/grafana` prefix in the edge proxy (`proxy_pass` without trailing slash) and add `X-Forwarded-Prefix /grafana`, `X-Forwarded-Host`, `X-Forwarded-Proto https`. Set Grafana to HTTP internally while keeping external `root_url` HTTPS. Ensure Grafana serves from the subpath by setting `grafana.ini.server.root_url` to `https://grafana.cloudradar.local/grafana/` and `serve_from_sub_path=true`.
- **Refs:** issue #296.

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
- **Resolution:** Re-applied taint `dedicated=control-plane:NoSchedule` and deleted app/redis/ESO pods so they rescheduled to the worker. **Follow-up:** switch to the official control-plane taint and document tolerations. (Refs: issue #203, #290)

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
