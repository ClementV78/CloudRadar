# Code Review: GitHub Actions CI/CD Workflows

**Files**:
- `.github/workflows/ci-infra.yml` (2 203 lines)
- `.github/workflows/ci-infra-destroy.yml` (274 lines)
- `.github/workflows/build-and-push.yml` (103 lines)

**Scope**: CI/CD pipeline infrastructure  
**Reviewer**: Copilot  
**Review Date**: 2026-02-23

---

## 1. Executive Summary

The three workflows form the backbone of CloudRadar's CI/CD: infrastructure provisioning with Terraform, teardown with safeguards, and multi-service container image builds. Together they represent ~2 580 lines of pipeline code and orchestrate a full deploy-to-production lifecycle (Terraform → k3s readiness → ArgoCD bootstrap → ESO secrets → Redis restore → smoke tests).

The workflows demonstrate mature DevOps practices: OIDC-based AWS authentication, infrastructure plan/apply separation, orphan resource scanning, Redis backup/restore with rotation, and progressive smoke testing through the edge proxy. The design is production-appropriate for a solo project while showcasing enterprise-grade patterns.

**Overall Assessment**: ✅ **Sound architecture with refactoring opportunities**

The main structural concern is the massive inline duplication of SSM helper functions in `ci-infra.yml`, which accounts for a significant portion of the file's 2 200+ lines. Functionally, all three workflows are correct and well-guarded.

---

## 2. Workflow: `ci-infra.yml` (Deploy Pipeline)

### 2.1 Architecture ✅

The workflow serves two distinct modes via a single file:

| Trigger | Mode | Jobs |
|---|---|---|
| `pull_request` | Validation only | `fmt` → `validate-plan` + `validate-modules` + `tfsec` |
| `workflow_dispatch` | Full deploy | `env-select` → `tf-validate` + `orphan-scan` → `tf-plan` → `tf-apply` → bootstrap chain → smoke tests |

**19 jobs** total orchestrate the dispatch path in a dependency chain:

```
env-select → tf-validate ─┐
                           ├─→ tf-plan → tf-apply → tf-outputs → k3s-ready-check
orphan-scan-pre-deploy ────┘                         │
                                                     ├─→ prometheus-crds → argocd-install → argocd-platform
                                                     │     → eso-ready-check → argocd-apps → eso-secrets-ready
                                                     │                                          │
                                                     ├─→ setup-eso-secrets                      ├─→ alertmanager-reenable
                                                     │                                          ├─→ redis-restore
                                                     └────────────────────────────────────────── └─→ smoke-tests
```

This is a well-thought-out dependency graph that ensures ordering (CRDs before ArgoCD, ESO before secrets, etc.).

### 2.2 PR Validation Path ✅

- **`fmt`**: Recursive format check on `infra/aws` — standard practice
- **`validate-plan`**: Matrix strategy across `bootstrap`, `live-dev`, `live-prod` — good coverage
- **`validate-modules`**: Iterates all modules with `init -backend=false` — validates module syntax independently
- **`tfsec`**: Security scan with explicit exclusions and `github_token` to avoid rate limits

**Strengths**:
- Matrix for plan across all roots avoids manual duplication
- `tfsec` exclusions are documented (cost-aware trade-offs for S3/DynamoDB encryption)
- Bootstrap uses local backend, live envs use remote — correct separation

### 2.3 Deploy Path ✅

#### Environment Selection & Guards ✅
- `env-select` validates `DNS_ZONE_NAME` exists for dev — fail-fast before expensive operations
- `tf-apply` requires explicit `auto_approve=true` — good deployment gate
- Job summaries on every step provide excellent visibility in workflow runs

#### Terraform Plan/Apply ✅
- var-file + dynamic `EXTRA_VARS` array pattern is clean and extensible
- Secrets (`GRAFANA_ADMIN_PASSWORD`, `PROMETHEUS_AUTH_PASSWORD`) injected via `-var` — correct approach
- CloudWatch alarm actions disabled before apply, re-enabled after — prevents false alerts during deploy

#### Hosted Zone Migration Step ⚠️
```yaml
- name: Detach hosted zone from env state (migration, dev only)
```
This step exists in both `tf-plan` and `tf-apply`. It appears to be a one-time migration. **Recommendation**: Add a comment with a target date for removal, or track cleanup in an issue so it doesn't persist forever.

### 2.4 Post-Apply Bootstrap Chain ✅

The chain (`k3s-ready-check` → `prometheus-crds` → `argocd-install` → `argocd-platform` → `eso-ready-check` → `argocd-apps` → `eso-secrets-ready`) follows the correct dependency order:

1. Node readiness before any k8s operations
2. CRDs before operator that consumes them
3. Operator before custom resources
4. ESO readiness before secret-dependent apps

**Strengths**:
- External scripts (`bootstrap-argocd-install.sh`, `bootstrap-eso-ready.sh`, etc.) keep workflow DRY for complex logic
- SSM commands include retry loops with exponential backoff
- Timeouts are explicit and generous enough for cold-start scenarios

### 2.5 Redis Restore ✅

The `REDIS-RESTORE` job implements a multi-gate safety pattern:

1. Input gate (`redis_backup_restore=true`)
2. Bucket availability gate
3. Backup existence gate (S3 prefix lookup)
4. Freshness gate (skip restore if Redis `/data` is not empty)

**Strengths**:
- Freshness check prevents overwriting live data
- Restore script is base64-encoded and shipped via SSM — good pattern for no-SSH environments
- Clear verdict summary in step summary

### 2.6 Smoke Tests ✅

Comprehensive end-to-end validation:
1. ArgoCD app sync/health check
2. `healthz` deployment rollout
3. Edge nginx listener check (port 443)
4. HTTP status check through edge proxy (`/healthz`, `/grafana/`, `/prometheus/`)

**Strengths**:
- Full-stack validation from cluster to edge
- Automatic k3s diagnostics collection on failure (`run_cluster_diagnostics`) — excellent for debugging
- Basic auth password properly masked with `::add-mask::`
- Retry logic on curl with exponential backoff

---

## 3. Workflow: `ci-infra-destroy.yml` (Teardown Pipeline)

### 3.1 Safety Guards ✅

- `confirm_destroy` must be exactly `"DESTROY"` — explicit destructive confirmation
- CloudWatch alarm actions disabled before destroy — prevents false alerts
- Alertmanager scaled to 0 before destroy — prevents cluster-internal alerts during teardown

### 3.2 Redis Backup Before Destroy ✅

- Ingester scaled to 0 before backup (stop writes)
- Backup script base64-shipped via SSM
- Rotation keeps last 3 backups — clean S3 lifecycle
- Complete SSM instance readiness check with exponential backoff

### 3.3 Post-Destroy Orphan Scan ✅

```yaml
- name: Orphan scan post-destroy (best effort)
  if: ${{ always() }}
  continue-on-error: true
```

Runs after destroy to detect leftover AWS resources. `continue-on-error: true` is correct — orphan scan failure should not mask the destroy result.

### 3.4 Observations

- **Single job**: Unlike `ci-infra`, this is a single monolithic job. Acceptable given the linear nature of destroy operations (no parallelism needed).
- **No environment protection**: relies on the `DESTROY` confirmation input. Consider adding GitHub Environment protection rules for an extra layer if prod is ever destroyed via this workflow.

---

## 4. Workflow: `build-and-push.yml` (Container Images)

### 4.1 Architecture ✅

Clean, minimal workflow:
- **Trigger**: `push` to `main` (with paths filter) and `pull_request` for build validation
- **Matrix**: 6 services (`ingester`, `processor`, `frontend`, `dashboard`, `health`, `admin-scale`)
- **`fail-fast: false`**: One service failure doesn't block others — correct for independent services

### 4.2 Tag Strategy ✅

```yaml
tags: |
  type=ref,event=pr
  type=ref,event=branch
  type=sha
  type=semver,pattern={{version}}
  type=raw,value=${{ env.APP_VERSION }},enable=${{ github.ref == 'refs/heads/main' }}
  type=raw,value=latest,enable=${{ github.ref == 'refs/heads/main' }}
```

Comprehensive tagging: PR ref, branch, SHA, semver, app version, and `latest`. `APP_VERSION` and `latest` only pushed on `main` — prevents PR builds from overwriting production tags.

### 4.3 Security ✅

- `GITHUB_TOKEN` used for GHCR auth — no external secrets needed
- Login step skipped on `pull_request` — PR builds validate but don't push
- Permissions scoped to `contents: read` + `packages: write` — least privilege

### 4.4 Build Performance ✅

- `docker/setup-buildx-action@v3` enables BuildKit
- `cache-from: type=gha` + `cache-to: type=gha,mode=max` — GitHub Actions cache for layer reuse across runs

### 4.5 Observations

- **`REPO_LOWER`**: Explicit lowercase conversion for GHCR paths — correct per AGENTS.md convention
- **`VERSION` file**: Single source of truth for app version, read with `tr -d` for whitespace safety

---

## 5. Cross-Cutting Concerns

### 5.1 SSM Helper Function Duplication 🔴 (Major)

`wait_for_instance_ready()`, `send_ssm_command()`, `is_uuid()`, and `poll_ssm_command()` are defined **6 times** inline across `ci-infra.yml`. These ~40-line functions are copy-pasted identically in:
- `k3s-ready-check`
- `alertmanager-reenable`
- `eso-secrets-ready`
- `smoke-tests` (3 times: ArgoCD wait, healthz check, edge check)

This accounts for ~600+ lines of duplication and is the primary driver of the file's 2 200-line size.

**Recommendation**: Extract these helpers into a shared script (e.g., `scripts/lib/ssm-helpers.sh`), and `source` it in each step. This would:
- Reduce `ci-infra.yml` by ~600 lines
- Eliminate drift risk between copies
- Make maintenance significantly easier

### 5.2 Action Version Pinning ⚠️ (Minor)

All actions use major-version tags (`@v4`, `@v3`, `@v5`):

| Action | Version |
|---|---|
| `actions/checkout` | `@v4` |
| `hashicorp/setup-terraform` | `@v3` |
| `aws-actions/configure-aws-credentials` | `@v4` |
| `docker/build-push-action` | `@v5` |
| `docker/login-action` | `@v3` |
| `docker/metadata-action` | `@v5` |
| `docker/setup-buildx-action` | `@v3` |
| `aquasecurity/tfsec-action` | `@v1.0.3` |

Major-version tags are the standard GitHub recommendation and acceptable. `tfsec-action` is pinned to `@v1.0.3` (exact patch) which is slightly stricter.

**Recommendation** (low priority): For maximum reproducibility, consider pinning to full SHA hashes (e.g., `actions/checkout@<sha>`). This is optional and mostly relevant for supply-chain security in enterprise contexts.

### 5.3 Workflow Summary Discipline ✅

Every job in `ci-infra.yml` emits a structured `GITHUB_STEP_SUMMARY` block and `::notice`/`::warning` annotations. This is excellent for visibility and debugging when reviewing workflow runs.

### 5.4 OIDC Authentication ✅

All three workflows use `id-token: write` permissions and `aws-actions/configure-aws-credentials` with `role-to-assume`. No static AWS credentials — clean OIDC pattern aligned with AGENTS.md security requirements.

### 5.5 Terraform Init Repetition ⚠️ (Minor)

`terraform init` with the same backend config is repeated in `tf-validate`, `tf-plan`, `tf-apply`, and `tf-outputs` (4 times). Each runs in a fresh runner, so this is technically necessary, but the `-backend-config` arguments are identical.

**Recommendation** (low priority): Consider a composite action or reusable workflow for the "init + configure AWS" preamble to reduce boilerplate.

### 5.6 Variable Assembly Duplication ⚠️ (Medium)

The `EXTRA_VARS` assembly block (DNS, aircraft DB, alerting, monitoring passwords) is duplicated almost identically between `tf-plan` and `tf-apply` (~50 lines each).

**Recommendation**: Extract the var-assembly logic into a shared script or use a matrix approach to avoid the near-identical blocks drifting apart.

---

## 6. Security Checklist

- ✅ No hardcoded credentials or secrets
- ✅ OIDC-based AWS authentication (no static keys)
- ✅ `::add-mask::` used for edge basic auth password
- ✅ Secrets passed via `-var` (not environment variables in logs)
- ✅ `DESTROY` confirmation guard on destructive workflow
- ✅ PR builds don't push images (login step conditional)
- ✅ Permissions scoped per workflow (`id-token: write`, `contents: read`, `packages: write`)
- ⚠️ `secrets.GRAFANA_ADMIN_PASSWORD` / `secrets.PROMETHEUS_AUTH_PASSWORD` used in bash `-var` interpolation — safe in GitHub Actions context but ensure these never appear in `terraform plan` output (Terraform marks sensitive vars)

---

## 7. Cost & FinOps Considerations

- ✅ PR path runs only `fmt`/`validate`/`plan`/`tfsec` — lightweight, no apply costs
- ✅ `fail-fast: false` in build matrix prevents re-running all 6 builds on transient failures
- ✅ GHA cache for Docker builds reduces build time and runner minutes
- ⚠️ The full dispatch path (19 jobs) runs many sequential SSM commands with waits — total runtime can be significant. Consider whether some checks can be parallelized (e.g., `setup-eso-secrets` could run in parallel with `k3s-ready-check`)

---

## 8. DevSecOps Gap Analysis — Towards a "Perfect" Pipeline

Cette section identifie les pratiques DevSecOps absentes ou partiellement implémentées, classées par impact.

### 8.1 Container Image Security (build-and-push) 🔴

#### Image Scanning — Absent
Aucun scan de vulnérabilités sur les images Docker produites. C'est le gap DevSecOps le plus visible.

**Recommendation** : Ajouter Trivy (gratuit, intégré GitHub) après le build :
```yaml
- name: Scan image for vulnerabilities
  uses: aquasecurity/trivy-action@master
  with:
    image-ref: ${{ env.REGISTRY }}/${{ env.REPO_LOWER }}/${{ matrix.service }}:${{ env.APP_VERSION }}
    format: table
    exit-code: 1            # fail on HIGH/CRITICAL
    severity: HIGH,CRITICAL
    ignore-unfixed: true
```

Impact : détecte les CVE dans les dépendances OS et applicatives **avant** qu'elles n'atteignent le cluster.

#### Dockerfile Linting — Absent
Les Dockerfiles (`maven:3.9.9-eclipse-temurin-17`, `node:20-alpine`, `python:3.11-slim`) ne sont pas lintés.

**Recommendation** : Ajouter Hadolint comme première étape du build :
```yaml
- name: Lint Dockerfile
  uses: hadolint/hadolint-action@v3.1.0
  with:
    dockerfile: ./src/${{ matrix.service }}/Dockerfile
```

Détecte : images non-pinned par digest, `apt-get` sans version, `COPY` avec permissions trop larges, absence de `USER` non-root, etc.

#### SBOM Generation — Absent
Aucune Software Bill of Materials n'est générée pour les images.

**Recommendation** (v1.1+) : Utiliser Syft ou l'option `sbom` de BuildKit :
```yaml
- name: Generate SBOM
  uses: anchore/sbom-action@v0
  with:
    image: ${{ env.REGISTRY }}/${{ env.REPO_LOWER }}/${{ matrix.service }}:${{ env.APP_VERSION }}
    format: spdx-json
    artifact-name: sbom-${{ matrix.service }}.spdx.json
```

Impact portfolio : démontre la conformité supply-chain (SLSA, EO 14028).

#### Image Signing — Absent
Les images ne sont pas signées cryptographiquement.

**Recommendation** (v2) : Cosign + Sigstore keyless signing :
```yaml
- name: Sign image
  uses: sigstore/cosign-installer@v3
- run: cosign sign --yes ${{ env.REGISTRY }}/${{ env.REPO_LOWER }}/${{ matrix.service }}@${{ steps.build.outputs.digest }}
```

Impact portfolio : démontre une politique zero-trust sur la supply-chain d'images.

### 8.2 Secrets & Code Scanning �

#### Secret Scanning in Code — Couvert par GitGuardian
GitGuardian Security Checks est activé au niveau du compte GitHub (GitHub App externe). Il scanne automatiquement chaque push et PR pour détecter les secrets exposés (clés AWS, tokens, mots de passe, etc.).

**Status** : ✅ Le cas nominal est couvert. GitGuardian détecte ~350+ types de secrets et s'exécute sans workflow dans le repo.

**Complément optionnel** (low priority) : Gitleaks pourrait compléter GitGuardian pour :
- Scanner l'historique git complet (commits anciens)
- Ajouter des patterns custom spécifiques au projet
- Fournir un gate CI explicite dans le repo (visible dans les checks PR)

Ce n'est pas prioritaire car GitGuardian couvre déjà l'essentiel.

#### Dependency Scanning — Absent
Pas de scan de dépendances applicatives (Maven, npm, pip) hors des images.

**Recommendation** : Activer Dependabot ou ajouter `trivy fs` sur `src/` :
```yaml
- name: Scan dependencies
  uses: aquasecurity/trivy-action@master
  with:
    scan-type: fs
    scan-ref: ./src/${{ matrix.service }}
    severity: HIGH,CRITICAL
```

### 8.3 Infrastructure Pipeline Gaps (ci-infra) 🟡

#### Terraform Version Pinning — Absent
`hashicorp/setup-terraform@v3` est utilisé **sans** `terraform_version`. La version Terraform varie selon ce que l'action installe par défaut (latest).

**Recommendation** :
```yaml
- name: Setup Terraform
  uses: hashicorp/setup-terraform@v3
  with:
    terraform_version: "1.7.5"  # pin to team-validated version
```

Impact : garantit la reproductibilité CI/local et évite les breaking changes silencieux.

#### Plan-Apply Drift — Risk moyen
Le plan et l'apply sont dans des jobs séparés. Chaque job fait `terraform init` + `plan`/`apply` indépendamment. Entre les deux, l'état réel peut changer (autre apply, changement AWS console).

**Recommendation** : Sauvegarder le plan en artefact et l'utiliser à l'apply :
```yaml
# In tf-plan:
- run: terraform plan -out=tfplan ...
- uses: actions/upload-artifact@v4
  with:
    name: tfplan-${{ inputs.environment }}
    path: ${{ needs.env-select.outputs.tf_dir }}/tfplan

# In tf-apply:
- uses: actions/download-artifact@v4
  with:
    name: tfplan-${{ inputs.environment }}
- run: terraform apply tfplan
```

Impact : élimine le risque de dérive entre plan review et apply effectif.

#### Concurrency Control — Absent
Deux dispatches simultanés sur le même environnement pourraient se chevaucher (le lock DynamoDB protège le state, mais pas les opérations SSM/k3s).

**Recommendation** :
```yaml
concurrency:
  group: ci-infra-${{ github.event.inputs.environment || 'pr' }}
  cancel-in-progress: false
```

Impact : empêche deux déploiements concurrents sur le même environnement.

#### Infracost / Cost Estimation — Absent
Aucune estimation de coût sur les PRs infra.

**Recommendation** (nice-to-have, free tier disponible) :
```yaml
- name: Infracost breakdown
  uses: infracost/actions/setup@v3
- run: infracost diff --path=${{ matrix.dir }} --format=json --out-file=/tmp/infracost.json
- uses: infracost/actions/comment@v3
  with:
    path: /tmp/infracost.json
```

Impact portfolio : démontre une culture FinOps intégrée au pipeline.

#### Drift Detection — Absent
Aucun `terraform plan` schedulé pour détecter les dérives entre le state et la réalité AWS.

**Recommendation** (v1.1+) : Ajouter un workflow `cron` qui exécute `terraform plan` quotidiennement et alerte si des changements sont détectés :
```yaml
on:
  schedule:
    - cron: '0 6 * * 1-5'  # weekdays 6 AM UTC
```

Impact : détecte les modifications manuelles en console ou les dérives de configuration.

### 8.4 Pipeline Observability 🟡

#### Notifications — Absentes
Aucune notification (Slack, email, webhook) en cas d'échec ou de succès du déploiement.

**Recommendation** : Ajouter un job final conditionnel :
```yaml
notify:
  if: ${{ always() }}
  needs: [smoke-tests]
  runs-on: ubuntu-latest
  steps:
    - uses: slackapi/slack-github-action@v2.0.0
      with:
        webhook: ${{ secrets.SLACK_WEBHOOK }}
        payload: '{"text": "ci-infra ${{ inputs.environment }}: ${{ needs.smoke-tests.result }}"}'
```

Impact : visibilité immédiate sur les déploiements sans consulter GitHub.

#### Workflow Run Time Tracking — Absent
Aucun suivi du temps d'exécution total du pipeline. Avec 19 jobs séquentiels et de nombreux polls SSM, le deploy peut durer 20-40 min sans visibilité.

**Recommendation** : Ajouter un job terminal qui agrège les durées et les publie dans le step summary.

### 8.5 Test Gate in Build Pipeline (build-and-push) 🟡

#### No Test Execution Before Build
Le workflow `build-and-push` ne lance aucun test (`mvn test`, `npm test`) avant de build et push les images. Les tests ne sont exécutés que si un workflow CI dédié existe par ailleurs.

**Recommendation** : Ajouter une étape de test avant le build, ou conditionner le push à la réussite d'un workflow CI (`ci-app`) via `workflow_run` :
```yaml
on:
  workflow_run:
    workflows: ["ci-app"]
    types: [completed]
    branches: [main]
```

Impact : garantit qu'aucune image non-testée n'est poussée en registry.

### 8.6 Maturity Summary

| Practice | Status | Priority | Effort |
|---|---|---|---|
| Container image scanning (Trivy) | ❌ Absent | **High** | ~15 min |
| Terraform version pinning | ❌ Absent | **High** | ~5 min |
| Concurrency control | ❌ Absent | **High** | ~5 min |
| Dockerfile linting (Hadolint) | ❌ Absent | Medium | ~10 min |
| Secret scanning | ✅ GitGuardian (App) | — | Déjà actif |
| Plan-apply artifact | ❌ Absent | Medium | ~30 min |
| Dependency scanning | ❌ Absent | Medium | ~15 min |
| Drift detection (scheduled plan) | ❌ Absent | Low | ~30 min |
| Cost estimation (Infracost) | ❌ Absent | Low | ~20 min |
| SBOM generation | ❌ Absent | Low | ~15 min |
| Image signing (Cosign) | ❌ Absent | Low | ~20 min |
| Notifications (Slack/email) | ❌ Absent | Low | ~15 min |
| Test gate before push | ⚠️ Partial | Low | ~20 min |

> **Quick wins** : Terraform version pin + concurrency control + Trivy scan = 3 ajouts, ~25 min de travail, impact DevSecOps significatif sur le portfolio.

---

## 9. Summary of Recommendations

| # | Priority | File | Recommendation |
|---|---|---|---|
| 1 | **High** | `ci-infra.yml` | Extract SSM helpers (`wait_for_instance_ready`, `send_ssm_command`, `is_uuid`, `poll_ssm_command`) into `scripts/lib/ssm-helpers.sh` — eliminates ~600 lines of duplication |
| 2 | **High** | `build-and-push.yml` | Add Trivy container image scanning after build (fail on HIGH/CRITICAL) |
| 3 | **High** | `ci-infra.yml` | Pin Terraform version explicitly in `setup-terraform` |
| 4 | **High** | `ci-infra.yml` | Add `concurrency` block to prevent parallel deploys on same environment |
| 5 | **Medium** | `ci-infra.yml` | Extract `EXTRA_VARS` assembly block into a shared script |
| 6 | **Medium** | `ci-infra.yml` | Save plan as artifact and use it in apply to prevent plan-apply drift |
| 7 | **Medium** | `build-and-push.yml` | Add Hadolint Dockerfile linting step |
| 8 | Low | All | _(Optional)_ Add Gitleaks as complementary secret scanner (GitGuardian already active) |
| 9 | Low | `ci-infra.yml` | Add removal target date or issue reference for the hosted zone migration step |
| 10 | Low | `ci-infra.yml` | Consider composite action for repeated Terraform init + AWS configure preamble |
| 11 | Low | All | Consider SHA-pinning actions for supply-chain security |
| 12 | Low | `ci-infra-destroy.yml` | Add GitHub Environment protection rules on prod |
| 13 | Low | `ci-infra.yml` | Add scheduled drift detection workflow |
| 14 | Low | `build-and-push.yml` | Add SBOM generation and image signing (Cosign) |
| 15 | Low | `ci-infra.yml` | Add deployment notifications (Slack/webhook) |

---

## 10. Per-Workflow Verdict

| Workflow | Lines | Jobs | Verdict |
|---|---|---|---|
| `ci-infra.yml` | 2 203 | 19 | ✅ Functionally excellent — needs refactoring + DevSecOps hardening |
| `ci-infra-destroy.yml` | 274 | 1 | ✅ Clean, well-guarded destructive workflow |
| `build-and-push.yml` | 103 | 1 (×6 matrix) | ✅ Good Docker CI — needs security scanning layer |

**Overall**: ✅ The pipeline infrastructure is functionally solid with production-grade operational patterns. Two axes d'amélioration se dégagent :
1. **Maintenabilité** : réduire les ~2 200 lignes de `ci-infra.yml` via l'extraction des helpers SSM
2. **DevSecOps** : ajouter le triptyque rapide (Trivy scan + TF version pin + concurrency control) pour passer d'un pipeline fonctionnel à un pipeline sécurisé et reproductible, valorisant le portfolio en entretien DevOps
