locals {
  dns_zone_name = trimsuffix(var.dns_zone_name, ".")
  dns_enabled   = local.dns_zone_name != ""
}

data "aws_route53_zone" "cloudradar" {
  count = local.dns_enabled ? 1 : 0

  name         = local.dns_zone_name
  private_zone = false
}

locals {
  dns_zone_id = local.dns_enabled ? data.aws_route53_zone.cloudradar[0].zone_id : ""

  dns_a_records = {
    (local.dns_zone_name)                 = module.edge.edge_public_ip
    ("grafana.${local.dns_zone_name}")    = module.edge.edge_public_ip
    ("prometheus.${local.dns_zone_name}") = module.edge.edge_public_ip
    ("app.${local.dns_zone_name}")        = module.edge.edge_public_ip
  }

  grafana_domain   = local.dns_enabled ? "grafana.${local.dns_zone_name}" : ""
  grafana_root_url = local.dns_enabled ? "https://${local.grafana_domain}/grafana/" : ""
}

resource "aws_route53_record" "edge_a" {
  for_each = local.dns_enabled ? local.dns_a_records : {}

  zone_id = local.dns_zone_id
  name    = each.key
  type    = "A"
  ttl     = 60
  records = [each.value]

  # Avoid create failures when the record already exists (e.g., state drift or partial destroy).
  allow_overwrite = true
}

resource "aws_ssm_parameter" "grafana_domain" {
  count = local.dns_enabled ? 1 : 0

  name      = "/cloudradar/grafana-domain"
  type      = "String"
  value     = local.grafana_domain
  overwrite = true
  tags      = local.tags
}

resource "aws_ssm_parameter" "grafana_root_url" {
  count = local.dns_enabled ? 1 : 0

  name      = "/cloudradar/grafana-root-url"
  type      = "String"
  value     = local.grafana_root_url
  overwrite = true
  tags      = local.tags
}
