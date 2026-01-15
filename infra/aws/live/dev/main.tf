provider "aws" {
  region = var.region
}

locals {
  // Common tags to keep resources discoverable across stacks.
  tags = merge(var.tags, {
    Environment = var.environment
    Project     = var.project
  })
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
