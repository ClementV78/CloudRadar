locals {
  dns_zone_name = trimsuffix(var.dns_zone_name, ".")
  dns_enabled   = local.dns_zone_name != ""
}

data "aws_route53_zone" "cloudradar" {
  count = local.dns_enabled ? 1 : 0

  name         = local.dns_zone_name
  private_zone = false
}
