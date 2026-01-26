# Troubleshooting Journal

This log tracks incidents and fixes in chronological order. Use it for debugging patterns and onboarding.

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
