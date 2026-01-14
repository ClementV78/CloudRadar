output "edge_instance_id" {
  description = "ID of the edge EC2 instance."
  value       = aws_instance.edge.id
}

output "edge_public_ip" {
  description = "Public IP of the edge EC2 instance."
  value       = aws_instance.edge.public_ip
}

output "edge_security_group_id" {
  description = "Security group ID for the edge instance."
  value       = aws_security_group.edge.id
}
