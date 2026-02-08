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

  name                                   = "${var.project}-${var.environment}"
  cidr_block                             = var.vpc_cidr_block
  azs                                    = var.azs
  public_subnet_cidrs                    = var.public_subnet_cidrs
  private_subnet_cidrs                   = var.private_subnet_cidrs
  private_route_nat_instance_id          = var.private_route_nat_instance_id
  enable_vpc_flow_logs                   = var.enable_vpc_flow_logs
  vpc_flow_logs_retention_in_days        = var.vpc_flow_logs_retention_in_days
  vpc_flow_logs_traffic_type             = var.vpc_flow_logs_traffic_type
  vpc_flow_logs_max_aggregation_interval = var.vpc_flow_logs_max_aggregation_interval
  tags                                   = local.tags
}
