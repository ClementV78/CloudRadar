locals {
  external_alerting_enabled = var.enable_external_alerting && trimspace(var.alert_email_endpoint) != ""
  alerting_sns_topic_arn    = local.external_alerting_enabled ? aws_sns_topic.external_alerting[0].arn : "disabled"
}

resource "aws_sns_topic" "external_alerting" {
  count = local.external_alerting_enabled ? 1 : 0

  name = "${var.project}-${var.environment}-external-alerting"

  tags = merge(local.tags, {
    Name      = "${var.project}-${var.environment}-external-alerting"
    Component = "alerting"
  })
}

resource "aws_sns_topic_subscription" "external_alerting_email" {
  count = local.external_alerting_enabled ? 1 : 0

  topic_arn = aws_sns_topic.external_alerting[0].arn
  protocol  = "email"
  endpoint  = trimspace(var.alert_email_endpoint)
}

resource "aws_ssm_parameter" "alerting_sns_topic_arn" {
  name        = "/cloudradar/alerting/sns-topic-arn"
  description = "Alertmanager SNS topic ARN (managed by Terraform)"
  type        = "String"
  value       = local.alerting_sns_topic_arn

  tags = merge(local.tags, {
    Name = "cloudradar-alerting-sns-topic-arn"
  })
}

resource "aws_ssm_parameter" "alerting_enabled" {
  name        = "/cloudradar/alerting/enabled"
  description = "Whether Alertmanager SNS notifications are enabled"
  type        = "String"
  value       = (local.external_alerting_enabled && var.alerts_enabled) ? "true" : "false"

  tags = merge(local.tags, {
    Name = "cloudradar-alerting-enabled"
  })
}

resource "aws_cloudwatch_metric_alarm" "k3s_server_status_check_failed" {
  count = local.external_alerting_enabled ? 1 : 0

  alarm_name          = "${var.project}-${var.environment}-k3s-server-status-check-failed"
  alarm_description   = "k3s server EC2 status check failed"
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed_Instance"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  actions_enabled     = var.alerts_enabled

  dimensions = {
    InstanceId = module.k3s.k3s_server_instance_id
  }

  alarm_actions = [aws_sns_topic.external_alerting[0].arn]
  ok_actions    = [aws_sns_topic.external_alerting[0].arn]

  tags = merge(local.tags, {
    Name      = "${var.project}-${var.environment}-k3s-server-status-check-failed"
    Component = "alerting"
  })
}

resource "aws_cloudwatch_metric_alarm" "edge_status_check_failed" {
  count = local.external_alerting_enabled ? 1 : 0

  alarm_name          = "${var.project}-${var.environment}-edge-status-check-failed"
  alarm_description   = "edge EC2 status check failed"
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed_Instance"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  actions_enabled     = var.alerts_enabled

  dimensions = {
    InstanceId = module.edge.edge_instance_id
  }

  alarm_actions = [aws_sns_topic.external_alerting[0].arn]
  ok_actions    = [aws_sns_topic.external_alerting[0].arn]

  tags = merge(local.tags, {
    Name      = "${var.project}-${var.environment}-edge-status-check-failed"
    Component = "alerting"
  })
}

resource "aws_cloudwatch_metric_alarm" "nat_status_check_failed" {
  count = local.external_alerting_enabled ? 1 : 0

  alarm_name          = "${var.project}-${var.environment}-nat-status-check-failed"
  alarm_description   = "NAT instance EC2 status check failed"
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed_Instance"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  actions_enabled     = var.alerts_enabled

  dimensions = {
    InstanceId = module.nat_instance.nat_instance_id
  }

  alarm_actions = [aws_sns_topic.external_alerting[0].arn]
  ok_actions    = [aws_sns_topic.external_alerting[0].arn]

  tags = merge(local.tags, {
    Name      = "${var.project}-${var.environment}-nat-status-check-failed"
    Component = "alerting"
  })
}

output "external_alerting_sns_topic_arn" {
  description = "SNS topic ARN used for external alerting notifications."
  value       = try(aws_sns_topic.external_alerting[0].arn, null)
}
