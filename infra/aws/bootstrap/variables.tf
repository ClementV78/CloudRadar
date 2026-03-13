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

variable "offline_site_enabled" {
  type        = bool
  description = "Enable persistent offline fallback stack (S3 + CloudFront + contact API + Route53 failover)."
  default     = false
}

variable "offline_site_bucket_name" {
  type        = string
  description = "Optional S3 bucket name for offline static assets. If empty, a name is derived from dns_zone_name."
  default     = ""
}

variable "offline_subdomain_label" {
  type        = string
  description = "Subdomain label used for offline preview page (offline.<dns_zone_name>)."
  default     = "offline"
}

variable "offline_primary_domain_label" {
  type        = string
  description = "Subdomain label for primary live endpoint used by Route53 failover health checks (live.<dns_zone_name>)."
  default     = "live"
}

variable "offline_contact_sender_local_part" {
  type        = string
  description = "Local-part for SES sender email used by offline contact form (<local-part>@<dns_zone_name>)."
  default     = "noreply"

  validation {
    condition     = length(trimspace(var.offline_contact_sender_local_part)) > 0
    error_message = "offline_contact_sender_local_part must not be empty."
  }
}

variable "offline_contact_recipient_email" {
  type        = string
  description = "Recipient email for offline contact form submissions."
  default     = ""

  validation {
    condition     = !var.offline_site_enabled || length(trimspace(var.offline_contact_recipient_email)) > 0
    error_message = "offline_contact_recipient_email must be set when offline_site_enabled is true."
  }
}

variable "offline_logs_retention_days" {
  type        = number
  description = "CloudWatch logs retention for offline Lambda/API logs."
  default     = 7
}

variable "offline_rate_limit_window_seconds" {
  type        = number
  description = "Rate-limit window size in seconds per source IP."
  default     = 900
}

variable "offline_rate_limit_max_hits" {
  type        = number
  description = "Maximum accepted contact submissions per IP and window."
  default     = 3
}

variable "offline_api_throttle_rate_limit" {
  type        = number
  description = "API Gateway throttle rate limit (requests per second)."
  default     = 5
}

variable "offline_api_throttle_burst_limit" {
  type        = number
  description = "API Gateway throttle burst limit."
  default     = 10
}

variable "offline_primary_health_path" {
  type        = string
  description = "Health-check path for Route53 primary live endpoint."
  default     = "/statusz"
}

variable "offline_primary_health_port" {
  type        = number
  description = "Health-check port for Route53 primary live endpoint."
  default     = 443
}

variable "tags" {
  type        = map(string)
  description = "Common tags for backend resources."
  default = {
    Project = "CloudRadar"
    Owner   = "infra"
  }
}
