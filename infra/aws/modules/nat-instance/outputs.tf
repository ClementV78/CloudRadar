output "nat_instance_id" {
  description = "ID of the NAT instance."
  value       = aws_instance.nat.id
}

output "nat_public_ip" {
  description = "Public IP of the NAT instance."
  value       = aws_instance.nat.public_ip
}

output "nat_security_group_id" {
  description = "Security group ID for the NAT instance."
  value       = aws_security_group.nat.id
}
