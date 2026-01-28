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
variable "grafana_admin_password" {
  description = "Grafana admin password. If empty, a random one will be generated."
  type        = string
  sensitive   = true
  default     = ""
}

variable "prometheus_auth_password" {
  description = "Prometheus authentication password. If empty, a random one will be generated."
  type        = string
  sensitive   = true
  default     = ""
}