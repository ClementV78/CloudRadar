# ADR-0016: External Secrets Operator for GitOps Secret Management

**Date:** 2026-01-29  
**Status:** Accepted  
**Context:** Issue #150

## Problem Statement

Currently, secrets are managed inconsistently:
- **App secrets (OpenSky):** Read directly from AWS SSM inside application code → couples app to AWS
- **Monitoring secrets (Grafana/Prometheus):** Manual Kubernetes Secrets → not GitOps-managed

This breaks GitOps principles and makes secret lifecycle management fragmented.

## Solution: External Secrets Operator (ESO)

Use **External Secrets Operator** to sync secrets from AWS SSM Parameter Store into Kubernetes Secrets declaratively.

### Architecture

```
AWS SSM Parameter Store (source of truth)
    ↓
ESO SecretStore (connection config)
    ↓
ESO ExternalSecret (what to sync)
    ↓
Kubernetes Secret (auto-created + auto-refreshed)
    ↓
Pod (mount or env var)
```

### Implementation: Option 4 (Instance Role)

**Chosen approach:** Use k3s EC2 instance IAM role (already exists).

**Why Option 4 over alternatives:**

| Option | Credentials | Isolation | Audit | Cost | Chosen? |
| --- | --- | --- | --- | --- | --- |
| **1: IRSA** | Temp (JWT) | ✅ High | ✅ Specific | ✅ Free | ❌ Complex for k3s |
| **2: Instance Role** | Temp (STS) | ⚠️ Low | ⚠️ Instance-level | ✅ Free | ✅ **Option 4 (simple)** |
| **3: IAM User + K8s Secret** | Static (keys) | ✅ High | ✅ User-level | ✅ Free | ❌ Static creds worse |
| **4: Instance Role (enhanced)** | Temp (STS) | ⚠️ Low | ⚠️ Instance-level | ✅ Free | ✅ **CHOSEN** |

**Trade-off accepted:** MVP project, solo development. Isolation vs credential lifecycle: credential lifecycle wins.

## Implementation Details

### Phase 1: Terraform IAM Policy
- Add `ssm:GetParameter` + `kms:Decrypt` to `k3s_nodes` IAM role
- Limited scope: `/cloudradar/*` parameters only
- KMS decrypt limited to SSM service

**File modified:** `infra/aws/modules/k3s/main.tf`

### Phase 2: ESO Installation
- Deploy External Secrets Operator via Helm (external-secrets chart)
- Namespace: `external-secrets` (standard)
- Managed by ArgoCD for GitOps

### Phase 3: Secrets Configuration
1. **SecretStore:** Configures ESO to read from SSM Parameter Store
2. **ExternalSecrets (3x):**
   - OpenSky API key → ingester
   - Grafana admin password
   - Prometheus basic auth

### Phase 4: Deployment Updates
- Grafana deployment: read password from ExternalSecret
- Prometheus deployment: read password from ExternalSecret
- Ingester deployment: read API key from env (remove SSM code)

### Phase 5: Cleanup
- Delete manual K8s Secrets (old way)

## Benefits

✅ **GitOps-first:** All secrets declared as code (ExternalSecrets YAML)  
✅ **Centralized:** SSM = single source of truth  
✅ **Auto-refresh:** ESO polls SSM (default: 1h)  
✅ **Least privilege:** Separate policy for ESO access  
✅ **No app coupling:** Apps read K8s Secrets, not SSM  
✅ **Temp credentials:** Instance role uses STS, no static keys  

## Risks & Mitigation

| Risk | Mitigation |
| --- | --- |
| Instance role too permissive | Document scope: `/cloudradar/*` only; use tags for audit |
| ESO misconfiguration | Use templates + examples; runbook with troubleshooting |
| Secret refresh delays | Document 1h refresh; manual sync available if urgent |

## Related Issues

- Issue #150: Refactor to use External Secrets Operator
- Depends on: ADR-0010 (Terraform + OIDC)

## References

- [External Secrets Operator Docs](https://external-secrets.io)
- [AWS SecretStore Provider](https://external-secrets.io/latest/provider/aws-secrets-manager/)
- [K8s Secrets best practices](https://kubernetes.io/docs/concepts/configuration/secret/)
