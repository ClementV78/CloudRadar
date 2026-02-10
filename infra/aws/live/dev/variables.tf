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
  default     = "t3a.medium"
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

variable "aircraft_reference_bucket_name" {
  description = "Optional aircraft reference data bucket name (created by bootstrap)."
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

variable "dns_zone_name" {
  description = "Optional Route53 hosted zone name for delegated subdomain (e.g., cloudradar.example.com)."
  type        = string
  default     = ""
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

variable "edge_admin_token_ssm_parameter_name" {
  description = "SSM parameter name storing the admin internal token."
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

variable "edge_admin_nodeport" {
  description = "NodePort for the admin scale service."
  type        = number
}

variable "edge_prometheus_nodeport" {
  description = "NodePort (or Traefik entrypoint port) for the Prometheus service."
  type        = number
}

variable "edge_grafana_nodeport" {
  description = "NodePort (or Traefik entrypoint port) for the Grafana service."
  type        = number
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

variable "enable_grafana_cloudwatch_read" {
  description = "Whether to grant the k3s node instance role read-only access to CloudWatch (metrics + logs) for Grafana."
  type        = bool
  default     = false
}
