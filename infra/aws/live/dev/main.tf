provider "aws" {
  region = var.region
}

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
}

data "aws_prefix_list" "s3" {
  name = "com.amazonaws.${var.region}.s3"
}

module "vpc" {
  source = "../../modules/vpc"

  name                 = "${var.project}-${var.environment}"
  cidr_block           = var.vpc_cidr_block
  azs                  = var.azs
  public_subnet_cidrs  = var.public_subnet_cidrs
  private_subnet_cidrs = var.private_subnet_cidrs
  tags                 = local.tags
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

  name                  = "${var.project}-${var.environment}"
  vpc_id                = module.vpc.vpc_id
  private_subnet_ids    = module.vpc.private_subnet_ids
  server_instance_type  = var.k3s_server_instance_type
  worker_instance_type  = var.k3s_worker_instance_type
  worker_min_size       = var.k3s_worker_min_size
  worker_desired        = var.k3s_worker_desired
  worker_max_size       = var.k3s_worker_max_size
  root_volume_size      = var.k3s_root_volume_size
  k3s_server_extra_args = var.k3s_server_extra_args
  k3s_agent_extra_args  = var.k3s_agent_extra_args
  tags                  = local.tags
}

module "edge" {
  source = "../../modules/edge"

  name                          = "${var.project}-${var.environment}"
  vpc_id                        = module.vpc.vpc_id
  public_subnet_id              = module.vpc.public_subnet_ids[0]
  private_subnet_cidrs          = var.private_subnet_cidrs
  allowed_cidrs                 = var.edge_allowed_cidrs
  instance_type                 = var.edge_instance_type
  root_volume_size              = var.edge_root_volume_size
  server_name                   = var.edge_server_name
  basic_auth_user               = var.edge_basic_auth_user
  basic_auth_ssm_parameter_name = var.edge_basic_auth_ssm_parameter_name
  region                        = var.region
  upstream_host                 = module.k3s.k3s_server_private_ip
  dashboard_upstream_port       = var.edge_dashboard_nodeport
  api_upstream_port             = var.edge_api_nodeport
  enable_http_redirect          = var.edge_enable_http_redirect
  tags                          = local.tags
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
