output "offline_distribution_domain_name" {
  description = "CloudFront domain name for offline fallback distribution."
  value       = try(aws_cloudfront_distribution.offline[0].domain_name, "")
}

output "offline_distribution_hosted_zone_id" {
  description = "CloudFront hosted zone ID for Route53 alias records."
  value       = try(aws_cloudfront_distribution.offline[0].hosted_zone_id, "")
}

output "offline_health_check_id" {
  description = "Route53 health-check ID used for failover primary record."
  value       = try(aws_route53_health_check.primary_live[0].id, "")
}
