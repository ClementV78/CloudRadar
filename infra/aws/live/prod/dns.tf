resource "aws_route53_zone" "cloudradar" {
  count = var.dns_zone_name == "" ? 0 : 1

  name = var.dns_zone_name
  tags = merge(var.tags, {
    Name = "${var.project}-${var.environment}-dns"
  })
}
