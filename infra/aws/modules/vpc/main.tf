// Core networking for one environment VPC.
// Summary: creates VPC + public/private subnets + route tables.
// Optional: private egress via NAT instance (route only when instance ID is provided).

locals {
  // Maps AZs to CIDRs to keep subnets aligned and predictable per environment.
  public_subnet_map = {
    for idx, az in var.azs : az => {
      az   = az
      cidr = var.public_subnet_cidrs[idx]
    }
  }
  // Same mapping for private subnets to avoid mismatched indices.
  private_subnet_map = {
    for idx, az in var.azs : az => {
      az   = az
      cidr = var.private_subnet_cidrs[idx]
    }
  }
}

resource "aws_vpc" "this" {
  cidr_block           = var.cidr_block
  enable_dns_hostnames = var.enable_dns_hostnames
  enable_dns_support   = var.enable_dns_support

  // Name tag eases discovery in the AWS console.
  tags = merge(var.tags, {
    Name = "${var.name}-vpc"
  })
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  // Internet gateway provides public egress for public subnets.
  tags = merge(var.tags, {
    Name = "${var.name}-igw"
  })
}

resource "aws_subnet" "public" {
  for_each = local.public_subnet_map

  vpc_id                  = aws_vpc.this.id
  availability_zone       = each.value.az
  cidr_block              = each.value.cidr
  map_public_ip_on_launch = true

  // Public subnets host edge components (e.g., Nginx).
  tags = merge(var.tags, var.public_subnet_tags, {
    Name = "${var.name}-public-${each.value.az}"
    Tier = "public"
  })
}

resource "aws_subnet" "private" {
  for_each = local.private_subnet_map

  vpc_id            = aws_vpc.this.id
  availability_zone = each.value.az
  cidr_block        = each.value.cidr

  // Private subnets host k3s nodes and internal services.
  tags = merge(var.tags, var.private_subnet_tags, {
    Name = "${var.name}-private-${each.value.az}"
    Tier = "private"
  })
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  // Public route table sends outbound traffic via IGW.
  tags = merge(var.tags, {
    Name = "${var.name}-public-rt"
  })
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.this.id

  // Private route table uses NAT instance only when provided.
  tags = merge(var.tags, {
    Name = "${var.name}-private-rt"
  })
}

data "aws_network_interface" "nat_primary" {
  count = var.private_route_nat_instance_id == null ? 0 : 1

  filter {
    name   = "attachment.instance-id"
    values = [var.private_route_nat_instance_id]
  }

  filter {
    name   = "attachment.device-index"
    values = ["0"]
  }
}

// Optional egress via NAT instance to keep costs low.
resource "aws_route" "private_nat_instance" {
  count = var.private_route_nat_instance_id == null ? 0 : 1

  route_table_id         = aws_route_table.private.id
  destination_cidr_block = "0.0.0.0/0"
  // Route to the NAT instance primary ENI (AWS provider v6 expects network_interface_id).
  network_interface_id = data.aws_network_interface.nat_primary[0].id
}

resource "aws_route_table_association" "public" {
  for_each = aws_subnet.public

  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "private" {
  for_each = aws_subnet.private

  subnet_id      = each.value.id
  route_table_id = aws_route_table.private.id
}
