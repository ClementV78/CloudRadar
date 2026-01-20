variable "name" {
  description = "Name prefix used for resource naming (include env)."
  type        = string
}

variable "vpc_id" {
  description = "VPC ID where k3s nodes are deployed."
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnet IDs for k3s nodes."
  type        = list(string)
}

variable "k3s_server_subnet_id" {
  description = "Optional subnet ID for the k3s server. Defaults to the first private subnet."
  type        = string
  default     = null
}

variable "server_instance_type" {
  description = "Instance type for the k3s server."
  type        = string
  default     = "t3.small"
}

variable "worker_instance_type" {
  description = "Instance type for k3s workers."
  type        = string
  default     = "t3.micro"
}

variable "worker_min_size" {
  description = "Minimum number of worker nodes in the ASG."
  type        = number
  default     = 1
}

variable "worker_desired" {
  description = "Desired number of worker nodes in the ASG."
  type        = number
}

variable "worker_max_size" {
  description = "Maximum number of worker nodes in the ASG."
  type        = number
  default     = 3
}

variable "root_volume_size" {
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

variable "enable_ebs_csi_policy" {
  description = "Whether to attach the AWS managed policy for the EBS CSI driver."
  type        = bool
  default     = false
}

variable "backup_bucket_name" {
  description = "Optional S3 bucket name for SQLite backups."
  type        = string
  default     = null
}

variable "tags" {
  description = "Tags applied to all resources."
  type        = map(string)
  default     = {}
}
