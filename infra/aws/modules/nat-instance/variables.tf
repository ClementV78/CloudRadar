variable "name" {
  description = "Name prefix used for resource naming (include env)."
  type        = string
}

variable "vpc_id" {
  description = "VPC ID where the NAT instance is deployed."
  type        = string
}

variable "public_subnet_id" {
  description = "Public subnet ID for the NAT instance."
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnet IDs that need outbound internet access."
  type        = list(string)
}

variable "private_subnet_cidrs" {
  description = "CIDR blocks for private subnets allowed to use NAT."
  type        = list(string)
}

variable "private_route_table_id" {
  description = "Route table ID for private subnets."
  type        = string
}

variable "instance_type" {
  description = "Instance type for the NAT instance."
  type        = string
  default     = "t3.nano"
}

variable "root_volume_size" {
  description = "Root volume size in GB for the NAT instance."
  type        = number
  default     = 8
}

variable "tags" {
  description = "Tags applied to all resources."
  type        = map(string)
  default     = {}
}
