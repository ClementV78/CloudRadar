# ADR-0013: GitOps Bootstrap Strategy for Kubernetes (ArgoCD)

## Architecture Decision Summary
ArgoCD is installed via **Helm** using **AWS SSM Run Command** triggered from **GitHub Actions**, with Terraform managing infrastructure and GitOps handling application delivery.  
This approach enables secure GitOps bootstrap on a **private k3s cluster**, avoids kubeconfig exposure, aligns with IAM-first security practices, respects FinOps constraints, and remains portable to **EKS**.

---

## Status
Accepted

---

## Links
- Related issue: https://github.com/ClementV78/CloudRadar/issues/104

---

## Context
The project requires a **GitOps controller (ArgoCD)** as part of the Kubernetes foundation.

Constraints:
- Kubernetes runs on **k3s on EC2**, in **private subnets**
- **No SSH**, IAM-first access model
- Infrastructure provisioned via **Terraform**
- CI orchestrated with **GitHub Actions**
- Personal portfolio project with **strong FinOps awareness**
- Future migration to **EKS** must remain possible

Several bootstrap strategies were evaluated.

---

## Options Considered

### Option A — Cloud-init (user_data)

| Aspect | Notes |
|------|------|
| ✅ Pros | Simple, self-contained, no extra orchestration |
| ❌ Cons | One-shot execution, limited retries/logs, imperative |
| Fit | Good for MVP, weaker for long-term or EKS |

---

### Option B — AWS SSM Run Command

| Aspect | Notes |
|------|------|
| ✅ Pros | No SSH, IAM-based, auditable, private subnet friendly |
|  | Clean separation between infra and bootstrap |
|  | Portable to EKS |
| ❌ Cons | AWS-coupled mechanism |
| Fit | **Strong balance of security, control, and cost** |

---

### Option C — Self-hosted GitHub Actions Runner (in VPC)

| Aspect | Notes |
|------|------|
| ✅ Pros | Enterprise-grade, Kubernetes-native interaction |
|  | No SSM dependency |
| ❌ Cons | Extra infra, higher ops burden, higher cost |
| Fit | Excellent for enterprise, overkill for personal MVP |

---

### Option D — AMI Pre-bake (Packer)

| Aspect | Notes |
|------|------|
| ✅ Pros | Fast boot, immutable infrastructure |
| ❌ Cons | Heavy pipeline, low flexibility, slow iteration |
| Fit | Not justified for MVP |

---

### Option E — Terraform Kubernetes / Helm Provider

| Aspect | Notes |
|------|------|
| ✅ Pros | Declarative, “everything in Terraform” |
| ❌ Cons | Fragile ordering, tight coupling with cluster state |
| Fit | Discouraged for complex Kubernetes workloads |

---

## Decision
**Option B (AWS SSM Run Command)** is selected.

ArgoCD is installed via **Helm**, executed from inside the private network through **SSM**, triggered by **GitHub Actions** after infrastructure provisioning.
ArgoCD bootstrapping is a one-time action; infrastructure remains Terraform-managed, while application delivery and drift are handled exclusively via GitOps.

This avoids:
- exposing the Kubernetes API,
- distributing kubeconfig,
- introducing unnecessary infrastructure components.

---

## Consequences
- Fully private cluster, no inbound access required
- Bootstrap actions are auditable and repeatable
- GitOps becomes the sole deployment mechanism post-bootstrap
- Minimal infrastructure footprint (FinOps-aligned)
- Smooth migration path to EKS

---

## Implementation Notes
- Terraform provisions EC2 + k3s
- GitHub Actions triggers SSM Run Command
- Helm installs ArgoCD (chart pinned to 9.3.4 by default)
- SSM remains the operational control plane
- Applications are deployed via GitOps only

---

## Future Evolution
A **self-hosted CI runner in the VPC** would be the preferred option in an enterprise production environment, but was intentionally excluded here to keep the MVP lean, cost-efficient, and focused on architectural clarity.
