locals {
  server_subnet_id  = var.k3s_server_subnet_id != null ? var.k3s_server_subnet_id : var.private_subnet_ids[0]
  server_args       = length(var.k3s_server_extra_args) > 0 ? " ${join(" ", var.k3s_server_extra_args)}" : ""
  agent_args        = length(var.k3s_agent_extra_args) > 0 ? " ${join(" ", var.k3s_agent_extra_args)}" : ""
  backup_bucket_arn = var.backup_bucket_name != null ? "arn:aws:s3:::${var.backup_bucket_name}" : null
}

data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-*-x86_64"]
  }
}

resource "random_password" "k3s_token" {
  length  = 32
  special = false
}

data "cloudinit_config" "server" {
  gzip          = false
  base64_encode = false

  part {
    content_type = "text/cloud-config"
    content = templatefile("${path.module}/templates/cloud-init-server.yaml", {
      k3s_token   = random_password.k3s_token.result
      server_args = local.server_args
    })
  }
}

data "cloudinit_config" "agent" {
  gzip          = false
  base64_encode = false

  part {
    content_type = "text/cloud-config"
    content = templatefile("${path.module}/templates/cloud-init-agent.yaml", {
      k3s_token         = random_password.k3s_token.result
      server_private_ip = aws_instance.k3s_server.private_ip
      agent_args        = local.agent_args
    })
  }
}

resource "aws_iam_role" "k3s_nodes" {
  name_prefix = "${var.name}-k3s-"

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

resource "aws_iam_instance_profile" "k3s_nodes" {
  name_prefix = "${var.name}-k3s-"
  role        = aws_iam_role.k3s_nodes.name
}

resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.k3s_nodes.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy_attachment" "ebs_csi_driver" {
  count = var.enable_ebs_csi_policy ? 1 : 0

  role       = aws_iam_role.k3s_nodes.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"
}

data "aws_iam_policy_document" "backup_bucket" {
  count = var.backup_bucket_name != null ? 1 : 0

  statement {
    actions = [
      "s3:AbortMultipartUpload",
      "s3:DeleteObject",
      "s3:GetBucketLocation",
      "s3:GetObject",
      "s3:ListBucket",
      "s3:ListBucketMultipartUploads",
      "s3:ListMultipartUploadParts",
      "s3:PutObject"
    ]

    resources = [
      local.backup_bucket_arn,
      "${local.backup_bucket_arn}/*"
    ]
  }
}

resource "aws_iam_policy" "backup_bucket" {
  count = var.backup_bucket_name != null ? 1 : 0

  name_prefix = "${var.name}-k3s-backups-"
  policy      = data.aws_iam_policy_document.backup_bucket[0].json
  tags        = var.tags
}

resource "aws_iam_role_policy_attachment" "backup_bucket" {
  count = var.backup_bucket_name != null ? 1 : 0

  role       = aws_iam_role.k3s_nodes.name
  policy_arn = aws_iam_policy.backup_bucket[0].arn
}

resource "aws_security_group" "k3s_nodes" {
  name_prefix = "${var.name}-k3s-"
  vpc_id      = var.vpc_id

  tags = merge(var.tags, {
    Name = "${var.name}-k3s-nodes"
  })
}

resource "aws_security_group_rule" "k3s_api" {
  type                     = "ingress"
  security_group_id        = aws_security_group.k3s_nodes.id
  from_port                = 6443
  to_port                  = 6443
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.k3s_nodes.id
  description              = "k3s API server"
}

resource "aws_security_group_rule" "k3s_kubelet" {
  type                     = "ingress"
  security_group_id        = aws_security_group.k3s_nodes.id
  from_port                = 10250
  to_port                  = 10250
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.k3s_nodes.id
  description              = "kubelet API"
}

resource "aws_security_group_rule" "k3s_flannel_vxlan" {
  type                     = "ingress"
  security_group_id        = aws_security_group.k3s_nodes.id
  from_port                = 8472
  to_port                  = 8472
  protocol                 = "udp"
  source_security_group_id = aws_security_group.k3s_nodes.id
  description              = "flannel VXLAN"
}

resource "aws_security_group_rule" "k3s_egress" {
  type              = "egress"
  security_group_id = aws_security_group.k3s_nodes.id
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
  description       = "Outbound access for updates and pulls"
}

resource "aws_instance" "k3s_server" {
  ami                         = data.aws_ami.al2023.id
  instance_type               = var.server_instance_type
  subnet_id                   = local.server_subnet_id
  vpc_security_group_ids      = [aws_security_group.k3s_nodes.id]
  iam_instance_profile        = aws_iam_instance_profile.k3s_nodes.name
  user_data                   = data.cloudinit_config.server.rendered
  user_data_replace_on_change = true

  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = var.root_volume_size
  }

  tags = merge(var.tags, {
    Name = "${var.name}-k3s-server"
    Role = "k3s-server"
  })
}

resource "aws_launch_template" "k3s_worker" {
  name_prefix   = "${var.name}-k3s-worker-"
  image_id      = data.aws_ami.al2023.id
  instance_type = var.worker_instance_type

  vpc_security_group_ids = [aws_security_group.k3s_nodes.id]
  user_data              = base64encode(data.cloudinit_config.agent.rendered)

  iam_instance_profile {
    name = aws_iam_instance_profile.k3s_nodes.name
  }

  metadata_options {
    http_tokens = "required"
  }

  block_device_mappings {
    device_name = "/dev/xvda"

    ebs {
      volume_type = "gp3"
      volume_size = var.root_volume_size
    }
  }

  tag_specifications {
    resource_type = "instance"
    tags = merge(var.tags, {
      Name = "${var.name}-k3s-worker"
      Role = "k3s-worker"
    })
  }

  tag_specifications {
    resource_type = "volume"
    tags = merge(var.tags, {
      Name = "${var.name}-k3s-worker-root"
      Role = "k3s-worker"
    })
  }
}

resource "aws_autoscaling_group" "k3s_workers" {
  name                = "${var.name}-k3s-workers"
  min_size            = var.worker_min_size
  desired_capacity    = var.worker_desired
  max_size            = var.worker_max_size
  vpc_zone_identifier = var.private_subnet_ids
  health_check_type   = "EC2"
  depends_on          = [aws_instance.k3s_server]

  launch_template {
    id      = aws_launch_template.k3s_worker.id
    version = "$Latest"
  }

  dynamic "tag" {
    for_each = merge(var.tags, {
      Name = "${var.name}-k3s-worker"
      Role = "k3s-worker"
    })

    content {
      key                 = tag.key
      value               = tag.value
      propagate_at_launch = true
    }
  }
}
