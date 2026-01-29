# External Secrets Operator (ESO) Implementation Summary

> Phase 3-5 implementation complete. All manifests, code refactoring, and documentation in place.

**Status: ✅ READY FOR DEPLOYMENT**

---

## Implementation Overview

### What Changed

1. **Terraform** (`infra/aws/modules/k3s/main.tf`)
   - Added `ssm:GetParameter` + `kms:Decrypt` permissions to k3s_nodes role
   - Permissions scoped to `/cloudradar/*` parameters and SSM KMS keys

2. **Kubernetes Manifests** (New files)
   - `k8s/platform/external-secrets/helmrelease.yaml` — ESO Helm deployment via ArgoCD
   - `k8s/platform/external-secrets/secretstore.yaml` — AWS SSM SecretStore (JWT via SA)
   - `k8s/apps/external-secrets/opensky-secret.yaml` — ExternalSecret for API credentials
   - `k8s/apps/external-secrets/grafana-secret.yaml` — ExternalSecret for Grafana admin
   - `k8s/apps/external-secrets/prometheus-secret.yaml` — ExternalSecret for Prometheus auth
   - `k8s/apps/external-secrets/prometheus-proxy.yaml` — nginx basic auth proxy

3. **Application Code** (`src/ingester/`)
   - Removed AWS SDK SSM dependency from pom.xml
   - Removed `SsmClient` bean from AppConfig.java
   - Simplified `OpenSkyProperties` (8 → 4 properties, removed SSM variants)
   - Simplified `OpenSkyEndpointProvider` (removed SSM client code)
   - Updated `k8s/apps/ingester/deployment.yaml` to use K8s Secrets

4. **Prometheus Integration** (`k8s/apps/monitoring/`)
   - Added basic auth proxy deployment (nginx)
   - Routes: Client → proxy (auth) → Prometheus internal service
   - Credentials from `prometheus-secret` ExternalSecret

---

## Architecture Achieved

```
SSM Parameter Store (source of truth)
    ↓
k3s nodes (IAM role: ssm:GetParameter)
    ↓
ESO SecretStore (JWT auth via service account)
    ↓
ExternalSecrets (3x: opensky, grafana, prometheus)
    ↓
K8s Secrets (auto-created & synced)
    ↓
Pods (env vars + mounts)
```

**Benefits:**
- No static credentials in code or manifests
- Automatic rotation (SSM → K8s Secret → Pod, default 1h)
- GitOps-native (ESO via ArgoCD)
- Centralized secret management (SSM as SSOT)
- Credentials never in image or process memory (until pod runtime)

---

## Deployment Checklist

### Pre-Deployment

- [ ] AWS SSM parameters created (see runbook for commands):
  ```
  /cloudradar/opensky/client-id ✓ (exists)
  /cloudradar/opensky/client-secret ✓ (exists)
  /cloudradar/opensky/base_url ✓ (exists)
  /cloudradar/opensky/token_url ✓ (exists)
  /cloudradar/grafana-admin-password (create)
  /cloudradar/prometheus-password (create)
  ```
  Note: `/cloudradar/prometheus-htpasswd` is generated automatically by `ci-infra` workflow

- [ ] Terraform applied: `terraform apply` in `infra/aws/live/dev`
- [ ] k3s cluster running with updated IAM role
- [ ] ArgoCD deployed and synced

### Deployment Order

1. **Create SSM Parameters** (manual)
   ```bash
   aws ssm put-parameter --name /cloudradar/grafana-admin-password --value "..." --type SecureString
   aws ssm put-parameter --name /cloudradar/prometheus-password --value "..." --type SecureString
   ```

2. **Run ci-infra workflow** (GitHub Actions)
   - Triggers `setup-eso-secrets` job after `tf-apply`
   - Generates `/cloudradar/prometheus-htpasswd` automatically
   - Creates `.htpasswd` format: `admin:bcrypt_hash`

3. **Terraform** (IAM setup)
   ```bash
   cd infra/aws/live/dev
   terraform plan -var-file=terraform.tfvars
   terraform apply -var-file=terraform.tfvars
   ```
   Workflow handles this via `workflow_dispatch`

4. **ArgoCD Application: external-secrets** (Helm deployment)
   ```bash
   git push  # Trigger ArgoCD sync
   # OR manually sync: argocd app sync external-secrets
   ```
   Wait for ESO pod to be ready:
   ```bash
   kubectl rollout status deployment/external-secrets -n external-secrets
   ```

5. **SecretStore** (ArgoCD auto-syncs via app)
   ```bash
   kubectl get secretstore -n external-secrets
   # Should show "Valid"
   ```

6. **ExternalSecrets** (ArgoCD auto-syncs)
   ```bash
   kubectl get externalsecret -A
   # Should show "SecretSynced" status for all 3
   ```

7. **Apps deployment** (no changes needed; apps updated to use ExternalSecret)
   - Ingester: Uses env vars from `opensky-secret`
   - Grafana: Uses env var from `grafana-secret`
   - Prometheus: Uses credentials from `prometheus-secret` (via proxy)

### Post-Deployment Verification

```bash
# Check ESO operator
kubectl get deployment -n external-secrets
kubectl logs deployment/external-secrets -n external-secrets

# Check SecretStore
kubectl describe secretstore -n external-secrets

# Check ExternalSecrets
kubectl get externalsecret -A
kubectl describe externalsecret opensky-secret -n external-secrets

# Check synced K8s Secrets
kubectl get secret -n external-secrets

# Check Ingester can read credentials
kubectl logs deployment/ingester -n ingester | grep -i opensky

# Test Prometheus proxy auth
kubectl port-forward svc/prometheus-basic-auth-proxy 8080:80 -n monitoring
curl -u admin:password http://localhost:8080/api/v1/query?query=up
```

---

## Troubleshooting

### ExternalSecret not syncing

```bash
# Check status
kubectl describe externalsecret opensky-secret -n external-secrets

# Common issues:
# 1. SecretStore not ready → Check annotation "secretstore.external-secrets.io/status"
# 2. IAM role permissions missing → Verify terraform applied
# 3. SSM parameter doesn't exist → Create it in AWS
# 4. ESO pod logs → kubectl logs -l app.kubernetes.io/name=external-secrets -n external-secrets
```

### K8s Secret not created

```bash
# Manual verification
kubectl get secret -n external-secrets
# If secret exists but ExternalSecret shows error, check:
kubectl describe externalsecret opensky-secret -n external-secrets
```

### App can't read secret

```bash
# Verify env var injection
kubectl get deployment ingester -n ingester -o yaml | grep -A 5 secretKeyRef

# Check if pod has secret mounted
kubectl get pod -n ingester
kubectl exec <pod> -c ingester -- env | grep OPENSKY
```

---

## Maintenance

### Rotate a Secret

```bash
# Update in SSM
aws ssm put-parameter \
  --name /cloudradar/opensky/client-id \
  --value "new-value" \
  --overwrite \
  --type SecureString

# Force immediate sync (optional; default 1h)
kubectl annotate externalsecret opensky-secret \
  external-secrets.io/force-sync="$(date +%s)" --overwrite

# Monitor sync
kubectl get externalsecret opensky-secret -w
```

### Monitor Refresh Intervals

```bash
# Check refresh status
kubectl get externalsecret -A -o wide

# Check ESO sync frequency
kubectl get externalsecret opensky-secret -o yaml | grep refreshInterval
```

---

## References

- **ADR-0016:** [External Secrets Operator Decision](../architecture/decisions/ADR-0016-2026-01-29-external-secrets-operator.md)
- **Runbook:** [ESO Setup & Operations](./external-secrets-operator.md)
- **Issue #150:** [GitHub Issue](https://github.com/ClementV78/CloudRadar/issues/150)
- **ESO Docs:** https://external-secrets.io
- **AWS SecretStore:** https://external-secrets.io/latest/provider/aws-secrets-manager/

---

## Summary

✅ **Phase 1:** Terraform IAM permissions  
✅ **Phase 2:** ESO Helm + SecretStore + ExternalSecrets  
✅ **Phase 3:** Ingester code refactoring  
✅ **Phase 4:** Prometheus basic auth proxy  
✅ **Phase 5:** This summary & deployment guide  

**Next:** Deploy to fresh k3s cluster following the deployment checklist above.
