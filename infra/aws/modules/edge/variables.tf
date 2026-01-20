variable "name" {
  description = "Name prefix used for resource naming (include env)."
  type        = string
}

variable "vpc_id" {
  description = "VPC ID where the edge instance is deployed."
  type        = string
}

variable "public_subnet_id" {
  description = "Public subnet ID for the edge instance."
  type        = string
}

variable "private_subnet_cidrs" {
  description = "Private subnet CIDR blocks for egress allow rules."
  type        = list(string)
}

variable "allowed_cidrs" {
  description = "CIDR blocks allowed to access the edge instance."
  type        = list(string)
}

variable "ssm_egress_enabled" {
  description = "Whether to allow HTTPS egress to SSM endpoints over the Internet."
  type        = bool
  default     = false
}

variable "instance_type" {
  description = "Instance type for the edge EC2 instance."
  type        = string
  default     = "t3.micro"
}

variable "ami_id" {
  description = "Optional AMI ID to pin the edge instance."
  type        = string
  default     = null
}

variable "root_volume_size" {
  description = "Root volume size in GB for the edge instance."
  type        = number
  default     = 20
}

variable "server_name" {
  description = "Nginx server_name for TLS termination."
  type        = string
  default     = "_"
}

variable "basic_auth_user" {
  description = "Basic auth username for the edge Nginx."
  type        = string
}

variable "basic_auth_ssm_parameter_name" {
  description = "SSM parameter name storing the basic auth password."
  type        = string
}

variable "region" {
  description = "AWS region for the SSM parameter lookup."
  type        = string
}

variable "upstream_host" {
  description = "Upstream host for reverse proxy (k3s node private IP)."
  type        = string
}

variable "dashboard_upstream_port" {
  description = "NodePort for the dashboard service."
  type        = number
}

variable "api_upstream_port" {
  description = "NodePort for the API service."
  type        = number
}

variable "health_upstream_port" {
  description = "NodePort for the health service."
  type        = number
}
variable "enable_http_redirect" {
  description = "Whether to redirect HTTP to HTTPS."
  type        = bool
  default     = true
}

variable "tags" {
  description = "Tags applied to all resources."
  type        = map(string)
  default     = {}
}
