output "vpc_id" {
  description = "ID of the VPC."
  value       = aws_vpc.this.id
}

output "internet_gateway_id" {
  description = "ID of the internet gateway."
  value       = aws_internet_gateway.this.id
}

output "public_subnet_ids" {
  description = "IDs of the public subnets."
  value       = [for subnet in aws_subnet.public : subnet.id]
}

output "private_subnet_ids" {
  description = "IDs of the private subnets."
  value       = [for subnet in aws_subnet.private : subnet.id]
}

output "public_route_table_id" {
  description = "ID of the public route table."
  value       = aws_route_table.public.id
}

output "private_route_table_id" {
  description = "ID of the private route table."
  value       = aws_route_table.private.id
}

output "vpc_flow_logs_log_group_name" {
  description = "CloudWatch Log Group name for VPC Flow Logs (null when disabled)."
  value       = try(aws_cloudwatch_log_group.vpc_flow_logs[0].name, null)
}

output "vpc_flow_logs_log_group_arn" {
  description = "CloudWatch Log Group ARN for VPC Flow Logs (null when disabled)."
  value       = try(aws_cloudwatch_log_group.vpc_flow_logs[0].arn, null)
}
