output "vpc_id" {
  description = "VPC ID for this environment."
  value       = module.vpc.vpc_id
}

output "public_subnet_ids" {
  description = "Public subnet IDs."
  value       = module.vpc.public_subnet_ids
}

output "private_subnet_ids" {
  description = "Private subnet IDs."
  value       = module.vpc.private_subnet_ids
}

output "public_route_table_id" {
  description = "Public route table ID."
  value       = module.vpc.public_route_table_id
}

output "private_route_table_id" {
  description = "Private route table ID."
  value       = module.vpc.private_route_table_id
}

output "nat_instance_id" {
  description = "NAT instance ID."
  value       = module.nat_instance.nat_instance_id
}

output "nat_public_ip" {
  description = "NAT instance public IP."
  value       = module.nat_instance.nat_public_ip
}

output "k3s_server_private_ip" {
  description = "Private IP of the k3s server."
  value       = module.k3s.k3s_server_private_ip
}

output "k3s_server_instance_id" {
  description = "Instance ID of the k3s server."
  value       = module.k3s.k3s_server_instance_id
}

output "k3s_security_group_id" {
  description = "Security group ID for k3s nodes."
  value       = module.k3s.k3s_security_group_id
}

output "k3s_worker_asg_name" {
  description = "Auto Scaling Group name for k3s workers."
  value       = module.k3s.k3s_worker_asg_name
}

output "edge_instance_id" {
  description = "Edge EC2 instance ID."
  value       = module.edge.edge_instance_id
}

output "edge_public_ip" {
  description = "Edge EC2 public IP."
  value       = module.edge.edge_public_ip
}

output "edge_basic_auth_user" {
  description = "Basic auth username for the edge Nginx."
  value       = var.edge_basic_auth_user
}

output "edge_basic_auth_ssm_parameter_name" {
  description = "SSM parameter name for the edge basic auth password."
  value       = var.edge_basic_auth_ssm_parameter_name
}

output "edge_security_group_id" {
  description = "Security group ID for the edge instance."
  value       = module.edge.edge_security_group_id
}

output "sqlite_backup_bucket_name" {
  description = "S3 bucket name for SQLite backups."
  value       = local.sqlite_backup_bucket_name
}

output "sqlite_backup_bucket_arn" {
  description = "S3 bucket ARN for SQLite backups."
  value       = "arn:aws:s3:::${local.sqlite_backup_bucket_name}"
}
