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
