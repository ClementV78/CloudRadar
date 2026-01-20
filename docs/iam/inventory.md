# IAM Inventory

Source of truth: Terraform modules under `infra/aws/modules/` and runbooks in `docs/runbooks/`.

## Users

| Name | Purpose | Source |
| --- | --- | --- |
| `CloudRadarBootstrapUser` | One-time AWS account bootstrap (MFA, budgets, OIDC, roles). | `docs/runbooks/aws-account-bootstrap.md` |
| `CloudRadarTerraformUser` | Local CLI user that assumes the Terraform role. | `docs/runbooks/aws-account-bootstrap.md` |

## Roles and Instance Profiles

| Name | Purpose | Source |
| --- | --- | --- |
| `CloudRadarBootstrapRole` | Bootstrap role assumed with MFA for initial account setup. | `docs/runbooks/aws-account-bootstrap.md` |
| `CloudRadarTerraformRole` | Main Terraform role (CI + local assume). | `docs/runbooks/aws-account-bootstrap.md` |
| `cloudradar-<env>-k3s-*` | k3s nodes role + instance profile for EC2. | `infra/aws/modules/k3s/main.tf` |
| `cloudradar-<env>-edge-*` | Edge EC2 role + instance profile. | `infra/aws/modules/edge/main.tf` |

## Policies

| Name | Purpose | Source |
| --- | --- | --- |
| `AmazonSSMManagedInstanceCore` | SSM access for k3s/edge instances. | `infra/aws/modules/k3s/main.tf`, `infra/aws/modules/edge/main.tf` |
| `AmazonEBSCSIDriverPolicy` | EBS CSI driver on k3s nodes. | `infra/aws/modules/k3s/main.tf` |
| `cloudradar-<env>-k3s-backups-*` | S3 backup bucket access for k3s nodes. | `infra/aws/modules/k3s/main.tf` |

## Notes

- Role and instance profile names use prefixes; use IAM search for `cloudradar-<env>-k3s` or `cloudradar-<env>-edge`.
- For bootstrap/role policies, see `docs/runbooks/aws-account-bootstrap.md`.
