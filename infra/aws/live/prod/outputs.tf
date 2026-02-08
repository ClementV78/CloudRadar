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

output "vpc_flow_logs_log_group_name" {
  description = "CloudWatch Log Group name for VPC Flow Logs (null when disabled)."
  value       = module.vpc.vpc_flow_logs_log_group_name
}

output "vpc_flow_logs_log_group_arn" {
  description = "CloudWatch Log Group ARN for VPC Flow Logs (null when disabled)."
  value       = module.vpc.vpc_flow_logs_log_group_arn
}

output "dns_zone_id" {
  description = "Route53 hosted zone ID for delegated subdomain (if enabled)."
  value       = try(data.aws_route53_zone.cloudradar[0].zone_id, "")
}

output "dns_zone_name" {
  description = "Route53 hosted zone name for delegated subdomain (if enabled)."
  value       = try(trimsuffix(data.aws_route53_zone.cloudradar[0].name, "."), "")
}

output "dns_zone_name_servers" {
  description = "Name servers for the delegated subdomain (if enabled)."
  value       = try(data.aws_route53_zone.cloudradar[0].name_servers, [])
}
