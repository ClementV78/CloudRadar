variable "region" {
  description = "AWS region for this environment."
  type        = string
}

variable "project" {
  description = "Project name used for tagging."
  type        = string
}

variable "environment" {
  description = "Environment name (dev, prod)."
  type        = string
}

variable "vpc_cidr_block" {
  description = "VPC CIDR block for this environment."
  type        = string
}

variable "azs" {
  description = "Availability zones to spread subnets across."
  type        = list(string)
}

variable "public_subnet_cidrs" {
  description = "CIDR blocks for public subnets."
  type        = list(string)
}

variable "private_subnet_cidrs" {
  description = "CIDR blocks for private subnets."
  type        = list(string)
}

variable "private_route_nat_instance_id" {
  description = "Optional NAT instance ID for private subnet egress."
  type        = string
  default     = null
}

variable "tags" {
  description = "Extra tags applied to all resources."
  type        = map(string)
  default     = {}
}

variable "dns_zone_name" {
  description = "Optional Route53 hosted zone name for delegated subdomain (e.g., cloudradar.example.com)."
  type        = string
  default     = ""
}

variable "processor_aircraft_db_enabled" {
  description = "Whether to enable aircraft reference DB enrichment in the processor (stored in SSM and injected via ESO)."
  type        = bool
  default     = false
}

variable "processor_aircraft_db_s3_uri" {
  description = "S3 URI to the aircraft reference SQLite artifact (stored in SSM and injected via ESO)."
  type        = string
  default     = ""

  validation {
    condition     = !var.processor_aircraft_db_enabled || length(trimspace(var.processor_aircraft_db_s3_uri)) > 0
    error_message = "processor_aircraft_db_s3_uri must be set when processor_aircraft_db_enabled is true."
  }
}

variable "processor_aircraft_db_sha256" {
  description = "Optional SHA256 for the aircraft reference SQLite artifact (stored in SSM and injected via ESO)."
  type        = string
  default     = ""

  validation {
    condition     = trimspace(var.processor_aircraft_db_sha256) == "" || can(regex("^[A-Fa-f0-9]{64}$", trimspace(var.processor_aircraft_db_sha256)))
    error_message = "processor_aircraft_db_sha256 must be empty or a 64-character hexadecimal SHA256."
  }
}
variable "grafana_admin_password" {
  description = "Grafana admin password. If empty, a random one will be generated."
  type        = string
  sensitive   = true
  default     = ""
}

variable "enable_vpc_flow_logs" {
  description = "Whether to enable VPC Flow Logs (CloudWatch Logs destination)."
  type        = bool
  default     = false
}

variable "vpc_flow_logs_retention_in_days" {
  description = "Retention in days for the VPC Flow Logs CloudWatch Log Group."
  type        = number
  default     = 3
}

variable "vpc_flow_logs_traffic_type" {
  description = "Traffic type captured by VPC Flow Logs (ACCEPT, REJECT, or ALL)."
  type        = string
  default     = "ALL"
}

variable "vpc_flow_logs_max_aggregation_interval" {
  description = "Maximum aggregation interval for Flow Logs (60 or 600 seconds)."
  type        = number
  default     = 60
}
