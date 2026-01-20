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
