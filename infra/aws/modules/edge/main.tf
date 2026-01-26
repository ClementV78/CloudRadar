locals {
  nginx_conf = templatefile("${path.module}/templates/nginx.conf.tpl", {
    server_name             = var.server_name
    upstream_host           = var.upstream_host
    dashboard_upstream_port = var.dashboard_upstream_port
    api_upstream_port       = var.api_upstream_port
    health_upstream_port    = var.health_upstream_port
    admin_upstream_port     = var.admin_upstream_port
    enable_http_redirect    = var.enable_http_redirect ? "1" : "0"
  })
}

data "aws_caller_identity" "current" {}

data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-minimal-*-x86_64"]
  }
}

resource "aws_iam_role" "edge" {
  name_prefix = "${var.name}-edge-"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ec2.amazonaws.com"
        }
      }
    ]
  })

  tags = var.tags
}

resource "aws_iam_instance_profile" "edge" {
  name_prefix = "${var.name}-edge-"
  role        = aws_iam_role.edge.name
}

resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.edge.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "edge_ssm_parameter" {
  name_prefix = "${var.name}-edge-ssm-"
  role        = aws_iam_role.edge.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "ssm:GetParameter",
          "ssm:GetParameters"
        ]
        Resource = [
          "arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter${var.basic_auth_ssm_parameter_name}",
          "arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter${var.admin_token_ssm_parameter_name}"
        ]
      }
    ]
  })
}

resource "aws_security_group" "edge" {
  name_prefix = "${var.name}-edge-"
  vpc_id      = var.vpc_id

  tags = merge(var.tags, {
    Name = "${var.name}-edge"
  })
}

resource "aws_security_group_rule" "edge_https" {
  type              = "ingress"
  security_group_id = aws_security_group.edge.id
  from_port         = 443
  to_port           = 443
  protocol          = "tcp"
  cidr_blocks       = var.allowed_cidrs
  description       = "HTTPS access"
}

resource "aws_security_group_rule" "edge_http" {
  count             = var.enable_http_redirect ? 1 : 0
  type              = "ingress"
  security_group_id = aws_security_group.edge.id
  from_port         = 80
  to_port           = 80
  protocol          = "tcp"
  cidr_blocks       = var.allowed_cidrs
  description       = "HTTP redirect"
}

resource "aws_security_group_rule" "edge_egress_private" {
  type              = "egress"
  security_group_id = aws_security_group.edge.id
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = var.private_subnet_cidrs
  description       = "Allow egress to private subnets"
}

resource "aws_security_group_rule" "edge_egress_ssm" {
  count             = var.ssm_egress_enabled ? 1 : 0
  type              = "egress"
  security_group_id = aws_security_group.edge.id
  from_port         = 443
  to_port           = 443
  protocol          = "tcp"
  cidr_blocks       = ["0.0.0.0/0"]
  description       = "Allow HTTPS egress for SSM when VPC endpoints are disabled"
}

resource "aws_instance" "edge" {
  ami                         = coalesce(var.ami_id, data.aws_ami.al2023.id)
  instance_type               = var.instance_type
  subnet_id                   = var.public_subnet_id
  vpc_security_group_ids      = [aws_security_group.edge.id]
  associate_public_ip_address = true
  iam_instance_profile        = aws_iam_instance_profile.edge.name
  user_data_replace_on_change = true
  user_data = templatefile("${path.module}/templates/user-data.sh.tpl", {
    server_name                    = var.server_name
    basic_auth_user                = var.basic_auth_user
    basic_auth_ssm_parameter_name  = var.basic_auth_ssm_parameter_name
    admin_token_ssm_parameter_name = var.admin_token_ssm_parameter_name
    aws_region                     = var.region
    nginx_conf                     = local.nginx_conf
  })

  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = var.root_volume_size
  }

  tags = merge(var.tags, {
    Name = "${var.name}-edge"
    Role = "edge"
  })
}
