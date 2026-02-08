variable "name" {
  description = "Name prefix used for resource naming (include env)."
  type        = string
}

variable "cidr_block" {
  description = "CIDR block for the VPC."
  type        = string
}

variable "azs" {
  description = "Availability zones used for subnets."
  type        = list(string)

  validation {
    condition     = length(var.azs) > 0
    error_message = "At least one availability zone is required."
  }
}

variable "public_subnet_cidrs" {
  description = "CIDR blocks for public subnets (one per AZ)."
  type        = list(string)

  validation {
    condition     = length(var.public_subnet_cidrs) == length(var.azs)
    error_message = "public_subnet_cidrs must match the number of azs."
  }
}

variable "private_subnet_cidrs" {
  description = "CIDR blocks for private subnets (one per AZ)."
  type        = list(string)

  validation {
    condition     = length(var.private_subnet_cidrs) == length(var.azs)
    error_message = "private_subnet_cidrs must match the number of azs."
  }
}

variable "enable_dns_hostnames" {
  description = "Whether to enable DNS hostnames in the VPC."
  type        = bool
  default     = true
}

variable "enable_dns_support" {
  description = "Whether to enable DNS resolution in the VPC."
  type        = bool
  default     = true
}

variable "private_route_nat_instance_id" {
  description = "Optional NAT instance ID for private subnet egress."
  type        = string
  default     = null
}

variable "tags" {
  description = "Common tags applied to all resources."
  type        = map(string)
  default     = {}
}

variable "public_subnet_tags" {
  description = "Extra tags for public subnets."
  type        = map(string)
  default     = {}
}

variable "private_subnet_tags" {
  description = "Extra tags for private subnets."
  type        = map(string)
  default     = {}
}

variable "enable_vpc_flow_logs" {
  description = "Whether to enable VPC Flow Logs (CloudWatch Logs destination)."
  type        = bool
  default     = false
}

variable "vpc_flow_logs_log_group_name" {
  description = "Optional CloudWatch Log Group name for VPC Flow Logs. Defaults to /cloudradar/<name>/vpc-flow-logs."
  type        = string
  default     = null
}

variable "vpc_flow_logs_retention_in_days" {
  description = "Retention in days for the VPC Flow Logs CloudWatch Log Group."
  type        = number
  default     = 3

  validation {
    condition     = var.vpc_flow_logs_retention_in_days >= 1
    error_message = "vpc_flow_logs_retention_in_days must be >= 1."
  }
}

variable "vpc_flow_logs_traffic_type" {
  description = "Traffic type captured by VPC Flow Logs (ACCEPT, REJECT, or ALL)."
  type        = string
  default     = "ALL"

  validation {
    condition     = contains(["ACCEPT", "REJECT", "ALL"], var.vpc_flow_logs_traffic_type)
    error_message = "vpc_flow_logs_traffic_type must be one of: ACCEPT, REJECT, ALL."
  }
}

variable "vpc_flow_logs_max_aggregation_interval" {
  description = "Maximum aggregation interval for Flow Logs (60 or 600 seconds). Lower values increase granularity and cost."
  type        = number
  default     = 60

  validation {
    condition     = contains([60, 600], var.vpc_flow_logs_max_aggregation_interval)
    error_message = "vpc_flow_logs_max_aggregation_interval must be 60 or 600."
  }
}
