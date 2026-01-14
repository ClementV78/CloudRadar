output "k3s_server_instance_id" {
  description = "ID of the k3s server instance."
  value       = aws_instance.k3s_server.id
}

output "k3s_server_private_ip" {
  description = "Private IP of the k3s server."
  value       = aws_instance.k3s_server.private_ip
}

output "k3s_security_group_id" {
  description = "Security group ID for k3s nodes."
  value       = aws_security_group.k3s_nodes.id
}

output "k3s_worker_asg_name" {
  description = "Auto Scaling Group name for k3s workers."
  value       = aws_autoscaling_group.k3s_workers.name
}
