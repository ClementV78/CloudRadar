provider "aws" {
  region = var.region
}

data "aws_caller_identity" "current" {}

locals {
  // Common tags to keep resources discoverable across stacks.
  tags = merge(var.tags, {
    Environment = var.environment
    Project     = var.project
  })

  ssm_vpc_endpoint_services = [
    "ssm",
    "ec2messages",
    "ssmmessages",
    "kms"
  ]

  // Health port defaults to the dashboard port unless explicitly set.
  edge_health_nodeport = var.edge_health_nodeport != null ? var.edge_health_nodeport : var.edge_dashboard_nodeport
  // Edge -> k3s allowed NodePorts, collected from edge upstream ports.
  edge_nodeport_rules = {
    dashboard  = var.edge_dashboard_nodeport
    api        = var.edge_api_nodeport
    health     = local.edge_health_nodeport
    admin      = var.edge_admin_nodeport
    grafana    = var.edge_grafana_nodeport
    prometheus = var.edge_prometheus_nodeport
  }
  // De-duplicate NodePorts so we don't create duplicate SG rules (e.g., Traefik shared ports).
  edge_nodeport_ports = distinct([
    for _, port in local.edge_nodeport_rules : port
    // Ports 80/443 are handled by dedicated ingress rules below.
    if port != null && port != 80 && port != 443
  ])
  // for_each only accepts sets of strings, so stringify ports for iteration.
  edge_nodeport_port_keys = toset([for port in local.edge_nodeport_ports : tostring(port)])

  sqlite_backup_bucket_name = var.sqlite_backup_bucket_name != null ? var.sqlite_backup_bucket_name : "${var.project}-${var.environment}-${data.aws_caller_identity.current.account_id}-sqlite-backups"
}

data "aws_prefix_list" "s3" {
  name = "com.amazonaws.${var.region}.s3"
}

module "vpc" {
  source = "../../modules/vpc"

  name                                   = "${var.project}-${var.environment}"
  cidr_block                             = var.vpc_cidr_block
  azs                                    = var.azs
  public_subnet_cidrs                    = var.public_subnet_cidrs
  private_subnet_cidrs                   = var.private_subnet_cidrs
  enable_vpc_flow_logs                   = var.enable_vpc_flow_logs
  vpc_flow_logs_retention_in_days        = var.vpc_flow_logs_retention_in_days
  vpc_flow_logs_traffic_type             = var.vpc_flow_logs_traffic_type
  vpc_flow_logs_max_aggregation_interval = var.vpc_flow_logs_max_aggregation_interval
  tags                                   = local.tags
}

module "nat_instance" {
  source = "../../modules/nat-instance"

  name                   = "${var.project}-${var.environment}"
  vpc_id                 = module.vpc.vpc_id
  public_subnet_id       = module.vpc.public_subnet_ids[0]
  private_subnet_ids     = module.vpc.private_subnet_ids
  private_subnet_cidrs   = var.private_subnet_cidrs
  private_route_table_id = module.vpc.private_route_table_id
  instance_type          = var.nat_instance_type
  root_volume_size       = var.nat_root_volume_size
  tags                   = local.tags
}

module "k3s" {
  source = "../../modules/k3s"

  name                           = "${var.project}-${var.environment}"
  vpc_id                         = module.vpc.vpc_id
  private_subnet_ids             = module.vpc.private_subnet_ids
  server_instance_type           = var.k3s_server_instance_type
  worker_instance_type           = var.k3s_worker_instance_type
  worker_min_size                = var.k3s_worker_min_size
  worker_desired                 = var.k3s_worker_desired
  worker_max_size                = var.k3s_worker_max_size
  root_volume_size               = var.k3s_root_volume_size
  k3s_server_extra_args          = var.k3s_server_extra_args
  k3s_agent_extra_args           = var.k3s_agent_extra_args
  serial_console_password_hash   = var.k3s_server_serial_console_password_hash
  enable_ebs_csi_policy          = true
  backup_bucket_name             = local.sqlite_backup_bucket_name
  enable_grafana_cloudwatch_read = var.enable_grafana_cloudwatch_read
  tags                           = local.tags
}

module "edge" {
  source = "../../modules/edge"

  name                           = "${var.project}-${var.environment}"
  vpc_id                         = module.vpc.vpc_id
  public_subnet_id               = module.vpc.public_subnet_ids[0]
  private_subnet_cidrs           = var.private_subnet_cidrs
  allowed_cidrs                  = var.edge_allowed_cidrs
  ssm_egress_enabled             = var.edge_ssm_egress_enabled
  ami_id                         = var.edge_ami_id
  instance_type                  = var.edge_instance_type
  root_volume_size               = var.edge_root_volume_size
  server_name                    = var.edge_server_name
  basic_auth_user                = var.edge_basic_auth_user
  basic_auth_ssm_parameter_name  = var.edge_basic_auth_ssm_parameter_name
  admin_token_ssm_parameter_name = var.edge_admin_token_ssm_parameter_name
  region                         = var.region
  upstream_host                  = module.k3s.k3s_server_private_ip
  dashboard_upstream_port        = var.edge_dashboard_nodeport
  api_upstream_port              = var.edge_api_nodeport
  health_upstream_port           = local.edge_health_nodeport
  admin_upstream_port            = var.edge_admin_nodeport
  prometheus_upstream_port       = var.edge_prometheus_nodeport
  grafana_upstream_port          = var.edge_grafana_nodeport
  enable_http_redirect           = var.edge_enable_http_redirect
  tags                           = local.tags

  depends_on = [
    aws_vpc_endpoint.edge_ssm,
    aws_vpc_endpoint.s3_gateway
  ]
}

resource "aws_security_group" "edge_ssm_endpoints" {
  count = var.edge_ssm_vpc_endpoints_enabled ? 1 : 0

  name_prefix = "${var.project}-${var.environment}-edge-ssm-"
  vpc_id      = module.vpc.vpc_id

  tags = merge(local.tags, {
    Name = "${var.project}-${var.environment}-edge-ssm-endpoints"
  })
}

resource "aws_security_group_rule" "edge_ssm_endpoints_ingress" {
  count = var.edge_ssm_vpc_endpoints_enabled ? 1 : 0

  type                     = "ingress"
  security_group_id        = aws_security_group.edge_ssm_endpoints[0].id
  from_port                = 443
  to_port                  = 443
  protocol                 = "tcp"
  source_security_group_id = module.edge.edge_security_group_id
  description              = "Allow SSM endpoint access from edge"
}

resource "aws_security_group_rule" "k3s_ssm_endpoints_ingress" {
  count = var.edge_ssm_vpc_endpoints_enabled ? 1 : 0

  type                     = "ingress"
  security_group_id        = aws_security_group.edge_ssm_endpoints[0].id
  from_port                = 443
  to_port                  = 443
  protocol                 = "tcp"
  source_security_group_id = module.k3s.k3s_security_group_id
  description              = "Allow SSM endpoint access from k3s nodes"
}
resource "aws_security_group_rule" "edge_ssm_endpoints_egress" {
  count = var.edge_ssm_vpc_endpoints_enabled ? 1 : 0

  type              = "egress"
  security_group_id = aws_security_group.edge_ssm_endpoints[0].id
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
  description       = "Allow endpoint egress"
}

resource "aws_security_group_rule" "edge_egress_s3" {
  count = var.edge_ssm_vpc_endpoints_enabled ? 1 : 0

  type              = "egress"
  security_group_id = module.edge.edge_security_group_id
  from_port         = 443
  to_port           = 443
  protocol          = "tcp"
  prefix_list_ids   = [data.aws_prefix_list.s3.id]
  description       = "Allow egress to S3 via gateway endpoint"
}
resource "aws_vpc_endpoint" "edge_ssm" {
  for_each = var.edge_ssm_vpc_endpoints_enabled ? toset(local.ssm_vpc_endpoint_services) : toset([])

  vpc_id              = module.vpc.vpc_id
  vpc_endpoint_type   = "Interface"
  service_name        = "com.amazonaws.${var.region}.${each.value}"
  subnet_ids          = module.vpc.private_subnet_ids
  security_group_ids  = [aws_security_group.edge_ssm_endpoints[0].id]
  private_dns_enabled = true

  tags = merge(local.tags, {
    Name = "${var.project}-${var.environment}-${each.value}-endpoint"
  })
}

resource "aws_vpc_endpoint" "s3_gateway" {
  count = var.edge_ssm_vpc_endpoints_enabled ? 1 : 0

  vpc_id            = module.vpc.vpc_id
  vpc_endpoint_type = "Gateway"
  service_name      = "com.amazonaws.${var.region}.s3"
  route_table_ids   = [module.vpc.public_route_table_id, module.vpc.private_route_table_id]

  tags = merge(local.tags, {
    Name = "${var.project}-${var.environment}-s3-endpoint"
  })
}

// Allow edge access to unique k3s NodePorts referenced by edge upstreams.
resource "aws_security_group_rule" "k3s_nodeports_from_edge" {
  for_each = local.edge_nodeport_port_keys

  type                     = "ingress"
  security_group_id        = module.k3s.k3s_security_group_id
  from_port                = tonumber(each.value)
  to_port                  = tonumber(each.value)
  protocol                 = "tcp"
  source_security_group_id = module.edge.edge_security_group_id
  description              = "Allow edge access to k3s nodeport ${each.value}"
}
# Allow edge to access k3s Ingress Controller (port 80)
resource "aws_security_group_rule" "k3s_ingress_from_edge" {
  type                     = "ingress"
  security_group_id        = module.k3s.k3s_security_group_id
  from_port                = 80
  to_port                  = 80
  protocol                 = "tcp"
  source_security_group_id = module.edge.edge_security_group_id
  description              = "Allow edge to k3s Ingress Controller (port 80)"
}

# Allow edge to access k3s Ingress Controller (port 443) for HTTPS
resource "aws_security_group_rule" "k3s_ingress_https_from_edge" {
  type                     = "ingress"
  security_group_id        = module.k3s.k3s_security_group_id
  from_port                = 443
  to_port                  = 443
  protocol                 = "tcp"
  source_security_group_id = module.edge.edge_security_group_id
  description              = "Allow edge to k3s Ingress Controller (port 443)"
}
