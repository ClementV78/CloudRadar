# Runbook: ci-infra workflow

This runbook explains what the `ci-infra` GitHub Actions workflow does and when to use it.

## When it runs

- On pull requests that touch `infra/**` or the workflow file itself.
- Manually via `workflow_dispatch` for controlled applies.

## What happens on pull_request

Jobs run in CI to validate Terraform safely (no apply):

1. `fmt`: `terraform fmt -check -recursive infra/aws`
2. `validate-modules`: init/validate each module under `infra/aws/modules/*` (backend disabled).
3. `validate-plan`:
   - `bootstrap`: local backend (`-backend=false`) + validate + plan with required vars.
   - `live-dev` and `live-prod`: remote backend init (S3 + DynamoDB) + validate + plan.
4. `tfsec`: static scan for common Terraform security issues (cost-aware exclusions).

### Which environment is targeted in CI?

- For `bootstrap`: no environment, local backend only.
- For `live-dev` and `live-prod`: the workflow initializes the remote backend using:
  - bucket: `${TF_STATE_BUCKET}`
  - lock table: `${TF_LOCK_TABLE_NAME}`
  - keys: `cloudradar/dev/terraform.tfstate` and `cloudradar/prod/terraform.tfstate`

These jobs do **not** apply changes. They only validate and plan.

## Manual apply (workflow_dispatch)

The `apply` job runs only when triggered manually:

- Select `environment` (`dev` or `prod`).
- Set `auto_approve=true` to allow apply.
- Uses the same S3/DynamoDB backend and the OIDC role.

## Manual destroy (workflow_dispatch)

Use the dedicated destroy workflow when you need to tear down an environment.

- Select `environment` (`dev` or `prod`).
- Set `confirm_destroy=DESTROY` to allow destruction.
- Uses the same S3/DynamoDB backend and the OIDC role.
- The workflow validates the selected root before destroying.

## Required repo variables

- `AWS_TERRAFORM_ROLE_ARN`
- `AWS_REGION`
- `TF_STATE_BUCKET`
- `TF_LOCK_TABLE_NAME`

## Related files

- Workflow: `.github/workflows/ci-infra.yml`
- Workflow: `.github/workflows/ci-infra-destroy.yml`
- Backend bootstrap runbook: `docs/runbooks/terraform-backend-bootstrap.md`
