variable "name" {
  description = "Bucket name."
  type        = string
}

variable "force_destroy" {
  description = "Whether to allow Terraform to delete non-empty buckets."
  type        = bool
  default     = false
}

variable "versioning_enabled" {
  description = "Whether to enable S3 versioning."
  type        = bool
  default     = true
}

variable "tags" {
  description = "Tags applied to all resources."
  type        = map(string)
  default     = {}
}
