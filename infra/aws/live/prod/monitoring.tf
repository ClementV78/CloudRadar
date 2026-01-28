# Monitoring: Prometheus + Grafana passwords management
# K8s Secrets are created manually via the observability runbook (see docs/runbooks/observability.md)

# Generate random passwords if not provided via variables
resource "random_password" "grafana_admin" {
  length  = 16
  special = true
}

resource "random_password" "prometheus_auth" {
  length  = 16
  special = true
}

# Use provided passwords or generated ones
locals {
  grafana_admin_password   = var.grafana_admin_password != "" ? var.grafana_admin_password : random_password.grafana_admin.result
  prometheus_auth_password = var.prometheus_auth_password != "" ? var.prometheus_auth_password : random_password.prometheus_auth.result
}

# Store passwords in SSM Parameter Store for audit trail and recovery
resource "aws_ssm_parameter" "grafana_admin_password" {
  name        = "/cloudradar/grafana/admin-password"
  description = "Grafana admin password (created by Terraform, stored for audit + recovery)"
  type        = "SecureString"
  value       = local.grafana_admin_password

  tags = merge(var.tags, {
    Name = "cloudradar-grafana-admin-password"
  })
}

resource "aws_ssm_parameter" "prometheus_auth_password" {
  name        = "/cloudradar/prometheus/auth-password"
  description = "Prometheus authentication password (created by Terraform, stored for audit + recovery)"
  type        = "SecureString"
  value       = local.prometheus_auth_password

  tags = merge(var.tags, {
    Name = "cloudradar-prometheus-auth-password"
  })
}

# Output passwords for CI/CD consumption (marked sensitive)
# These are used by GitHub Actions workflows
output "grafana_admin_password" {
  description = "Grafana admin password (sensitive) - use in K8s Secret creation"
  value       = local.grafana_admin_password
  sensitive   = true
}

output "prometheus_auth_password" {
  description = "Prometheus auth password (sensitive) - store for future use"
  value       = local.prometheus_auth_password
  sensitive   = true
}
