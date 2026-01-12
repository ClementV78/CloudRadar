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
