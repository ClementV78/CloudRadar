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

variable "tags" {
  description = "Extra tags applied to all resources."
  type        = map(string)
  default     = {}
}

variable "nat_instance_type" {
  description = "Instance type for the NAT instance."
  type        = string
  default     = "t3.nano"
}

variable "nat_root_volume_size" {
  description = "Root volume size in GB for the NAT instance."
  type        = number
  default     = 8
}

variable "k3s_server_instance_type" {
  description = "Instance type for the k3s server."
  type        = string
  default     = "t3.small"
}

variable "k3s_worker_instance_type" {
  description = "Instance type for k3s workers."
  type        = string
  default     = "t3.micro"
}

variable "k3s_worker_min_size" {
  description = "Minimum number of k3s worker nodes."
  type        = number
  default     = 1
}

variable "k3s_worker_desired" {
  description = "Desired number of k3s worker nodes."
  type        = number
}

variable "k3s_worker_max_size" {
  description = "Maximum number of k3s worker nodes."
  type        = number
  default     = 3
}

variable "k3s_root_volume_size" {
  description = "Root volume size in GB for k3s nodes."
  type        = number
  default     = 20
}

variable "k3s_server_extra_args" {
  description = "Extra CLI arguments for the k3s server."
  type        = list(string)
  default     = []
}

variable "k3s_agent_extra_args" {
  description = "Extra CLI arguments for k3s agents."
  type        = list(string)
  default     = []
}

variable "k3s_server_serial_console_password_hash" {
  description = "Optional password hash for ec2-user to enable EC2 Serial Console access."
  type        = string
  default     = ""
  sensitive   = true
}

variable "sqlite_backup_bucket_name" {
  description = "Optional SQLite backup bucket name (created by bootstrap)."
  type        = string
  default     = null
}

variable "edge_instance_type" {
  description = "Instance type for the edge EC2 instance."
  type        = string
  default     = "t3.micro"
}

variable "edge_ami_id" {
  description = "Optional AMI ID to pin the edge instance."
  type        = string
  default     = null
}

variable "edge_root_volume_size" {
  description = "Root volume size in GB for the edge instance."
  type        = number
  default     = 40
}

variable "edge_allowed_cidrs" {
  description = "CIDR blocks allowed to access the edge instance."
  type        = list(string)
}

variable "edge_ssm_egress_enabled" {
  description = "Whether to allow HTTPS egress for SSM when endpoints are disabled."
  type        = bool
  default     = false
}

variable "edge_server_name" {
  description = "Nginx server_name for TLS termination."
  type        = string
  default     = "_"
}

variable "edge_basic_auth_user" {
  description = "Basic auth username for the edge Nginx."
  type        = string
}

variable "edge_basic_auth_ssm_parameter_name" {
  description = "SSM parameter name storing the basic auth password."
  type        = string
}

variable "edge_dashboard_nodeport" {
  description = "NodePort for the dashboard service."
  type        = number
}

variable "edge_api_nodeport" {
  description = "NodePort for the API service."
  type        = number
}

variable "edge_health_nodeport" {
  description = "NodePort for the health service. Defaults to the dashboard nodeport when null."
  type        = number
  default     = null
}

variable "edge_enable_http_redirect" {
  description = "Whether to redirect HTTP to HTTPS."
  type        = bool
  default     = true
}

variable "edge_ssm_vpc_endpoints_enabled" {
  description = "Whether to create VPC interface endpoints for SSM, EC2 messages, and KMS."
  type        = bool
  default     = true
}
