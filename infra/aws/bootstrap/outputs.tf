output "state_bucket_name" {
  value       = aws_s3_bucket.tf_state.bucket
  description = "Terraform state bucket name."
}

output "lock_table_name" {
  value       = aws_dynamodb_table.tf_lock.name
  description = "Terraform lock table name."
}

output "backup_bucket_name" {
  value       = var.backup_bucket_name != null && var.backup_bucket_name != "" ? module.sqlite_backups[0].bucket_name : null
  description = "SQLite backup bucket name (if created)."
}

output "backup_bucket_arn" {
  value       = var.backup_bucket_name != null && var.backup_bucket_name != "" ? module.sqlite_backups[0].bucket_arn : null
  description = "SQLite backup bucket ARN (if created)."
}

output "aircraft_reference_bucket_name" {
  value       = var.aircraft_reference_bucket_name != null && var.aircraft_reference_bucket_name != "" ? module.aircraft_reference[0].bucket_name : null
  description = "Aircraft reference data bucket name (if created)."
}

output "aircraft_reference_bucket_arn" {
  value       = var.aircraft_reference_bucket_name != null && var.aircraft_reference_bucket_name != "" ? module.aircraft_reference[0].bucket_arn : null
  description = "Aircraft reference data bucket ARN (if created)."
}

output "dns_zone_id" {
  value       = try(aws_route53_zone.cloudradar[0].zone_id, "")
  description = "Route53 hosted zone ID for delegated subdomain (if created)."
}

output "dns_zone_name" {
  value       = try(trimsuffix(aws_route53_zone.cloudradar[0].name, "."), "")
  description = "Route53 hosted zone name for delegated subdomain (if created)."
}

output "dns_zone_name_servers" {
  value       = try(aws_route53_zone.cloudradar[0].name_servers, [])
  description = "Name servers for the delegated subdomain (if created)."
}

output "offline_domain" {
  value       = local.offline_enabled ? local.offline_domain : ""
  description = "Offline preview domain (if offline stack is enabled)."
}

output "offline_cloudfront_domain_name" {
  value       = local.offline_enabled ? aws_cloudfront_distribution.offline[0].domain_name : ""
  description = "CloudFront domain for the offline fallback distribution."
}

output "offline_contact_api_endpoint" {
  value       = local.offline_enabled ? aws_apigatewayv2_api.offline_contact[0].api_endpoint : ""
  description = "Direct API Gateway endpoint for offline contact form."
}

output "offline_contact_sender_email" {
  value       = local.offline_enabled ? local.offline_sender_email : ""
  description = "Computed SES sender email used by the offline contact Lambda."
}
