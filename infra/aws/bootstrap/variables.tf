variable "region" {
  type        = string
  description = "AWS region for backend resources."
  default     = "us-east-1"
}

variable "state_bucket_name" {
  type        = string
  description = "S3 bucket name for Terraform state."
}

variable "lock_table_name" {
  type        = string
  description = "DynamoDB table name for Terraform state locking."
  default     = "cloudradar-tf-lock"
}

variable "backup_bucket_name" {
  type        = string
  description = "Optional S3 bucket name for SQLite backups."
  default     = null
}

variable "aircraft_reference_bucket_name" {
  type        = string
  description = "Optional S3 bucket name for aircraft reference data artifacts."
  default     = null
}

variable "dns_zone_name" {
  type        = string
  description = "Optional Route53 hosted zone name for delegated subdomain (e.g., cloudradar.example.com)."
  default     = ""
}

variable "tags" {
  type        = map(string)
  description = "Common tags for backend resources."
  default = {
    Project = "CloudRadar"
    Owner   = "infra"
  }
}
