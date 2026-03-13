provider "aws" {
  region = var.region
}

locals {
  dns_zone_name = trimsuffix(var.dns_zone_name, ".")

  offline_enabled          = var.offline_site_enabled && local.dns_zone_name != ""
  offline_failover_enabled = local.offline_enabled && var.offline_route53_failover_enabled

  offline_domain = local.offline_enabled ? "${var.offline_subdomain_label}.${local.dns_zone_name}" : ""
  live_domain    = local.offline_enabled ? "${var.offline_primary_domain_label}.${local.dns_zone_name}" : ""
  offline_sender_email = local.offline_enabled ? (
    "${trimspace(var.offline_contact_sender_local_part)}@${local.dns_zone_name}"
  ) : ""
  offline_certificate_validation_domains = local.offline_enabled ? [
    local.dns_zone_name,
    local.offline_domain
  ] : []

  offline_bucket_name = local.offline_enabled ? (
    trimspace(var.offline_site_bucket_name) != ""
    ? trimspace(var.offline_site_bucket_name)
    : "cloudradar-offline-${replace(local.dns_zone_name, ".", "-")}"
  ) : ""

  zone_id = try(data.aws_route53_zone.cloudradar[0].zone_id, "")

  offline_static_files = local.offline_enabled ? {
    "index.html" = {
      source_path  = "${path.module}/offline-site/index.html"
      content_type = "text/html; charset=utf-8"
    }
    "styles.css" = {
      source_path  = "${path.module}/offline-site/styles.css"
      content_type = "text/css; charset=utf-8"
    }
    "app.js" = {
      source_path  = "${path.module}/offline-site/app.js"
      content_type = "application/javascript; charset=utf-8"
    }
  } : {}

  offline_screenshot_files = local.offline_enabled ? {
    "screenshots/dashboard.png" = {
      source_path  = "${path.module}/../../../docs/screenshots/dashboard.png"
      content_type = "image/png"
    }
    "screenshots/grafana-app-telemetry.png" = {
      source_path  = "${path.module}/../../../docs/screenshots/grafana-app-telemetry.png"
      content_type = "image/png"
    }
    "screenshots/grafana-ops.png" = {
      source_path  = "${path.module}/../../../docs/screenshots/grafana-ops.png"
      content_type = "image/png"
    }
  } : {}
}

data "aws_route53_zone" "cloudradar" {
  count = local.offline_enabled ? 1 : 0

  name         = local.dns_zone_name
  private_zone = false
}

resource "aws_s3_bucket" "offline_site" {
  count = local.offline_enabled ? 1 : 0

  bucket = local.offline_bucket_name
  tags   = merge(var.tags, { Name = "cloudradar-offline-site" })
}

resource "aws_s3_bucket_versioning" "offline_site" {
  count = local.offline_enabled ? 1 : 0

  bucket = aws_s3_bucket.offline_site[0].id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "offline_site" {
  count = local.offline_enabled ? 1 : 0

  bucket = aws_s3_bucket.offline_site[0].id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "offline_site" {
  count = local.offline_enabled ? 1 : 0

  bucket                  = aws_s3_bucket.offline_site[0].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "offline_site" {
  count = local.offline_enabled ? 1 : 0

  bucket = aws_s3_bucket.offline_site[0].id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_object" "offline_static" {
  for_each = local.offline_static_files

  bucket       = aws_s3_bucket.offline_site[0].id
  key          = each.key
  source       = each.value.source_path
  etag         = filemd5(each.value.source_path)
  content_type = each.value.content_type
}

resource "aws_s3_object" "offline_screenshots" {
  for_each = local.offline_screenshot_files

  bucket       = aws_s3_bucket.offline_site[0].id
  key          = each.key
  source       = each.value.source_path
  etag         = filemd5(each.value.source_path)
  content_type = each.value.content_type
}

resource "aws_ses_domain_identity" "offline_sender_domain" {
  count = local.offline_enabled ? 1 : 0

  domain = local.dns_zone_name
}

resource "aws_route53_record" "offline_sender_domain_verification" {
  count = local.offline_enabled ? 1 : 0

  allow_overwrite = true
  zone_id         = local.zone_id
  name            = "_amazonses.${local.dns_zone_name}"
  type            = "TXT"
  ttl             = 600
  records         = [aws_ses_domain_identity.offline_sender_domain[0].verification_token]
}

resource "aws_ses_domain_dkim" "offline_sender_domain" {
  count = local.offline_enabled ? 1 : 0

  domain = aws_ses_domain_identity.offline_sender_domain[0].domain
}

resource "aws_route53_record" "offline_sender_domain_dkim" {
  count = local.offline_enabled ? 3 : 0

  allow_overwrite = true
  zone_id         = local.zone_id
  name            = "${aws_ses_domain_dkim.offline_sender_domain[0].dkim_tokens[count.index]}._domainkey.${local.dns_zone_name}"
  type            = "CNAME"
  ttl             = 600
  records         = ["${aws_ses_domain_dkim.offline_sender_domain[0].dkim_tokens[count.index]}.dkim.amazonses.com"]
}

resource "aws_dynamodb_table" "offline_rate_limit" {
  count = local.offline_enabled ? 1 : 0

  name         = "cloudradar-offline-contact-rate-limit"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"

  attribute {
    name = "pk"
    type = "S"
  }

  ttl {
    attribute_name = "expires_at"
    enabled        = true
  }

  tags = merge(var.tags, { Name = "cloudradar-offline-contact-rate-limit" })
}

data "archive_file" "offline_contact_lambda" {
  count = local.offline_enabled ? 1 : 0

  type        = "zip"
  source_file = "${path.module}/lambda/contact_demo.py"
  output_path = "${path.module}/.terraform/offline-contact-lambda.zip"
}

data "aws_iam_policy_document" "offline_contact_lambda_assume" {
  count = local.offline_enabled ? 1 : 0

  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "offline_contact_lambda" {
  count = local.offline_enabled ? 1 : 0

  name_prefix        = "cloudradar-offline-contact-lambda-"
  assume_role_policy = data.aws_iam_policy_document.offline_contact_lambda_assume[0].json
  tags               = var.tags
}

resource "aws_iam_role_policy" "offline_contact_lambda" {
  count = local.offline_enabled ? 1 : 0

  name_prefix = "cloudradar-offline-contact-lambda-"
  role        = aws_iam_role.offline_contact_lambda[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = "arn:aws:logs:${var.region}:*:*"
      },
      {
        Effect = "Allow"
        Action = [
          "dynamodb:UpdateItem"
        ]
        Resource = aws_dynamodb_table.offline_rate_limit[0].arn
      },
      {
        Effect = "Allow"
        Action = [
          "ses:SendEmail",
          "ses:SendRawEmail"
        ]
        Resource = "arn:aws:ses:${var.region}:*:identity/${local.dns_zone_name}"
      }
    ]
  })
}

resource "aws_cloudwatch_log_group" "offline_contact_lambda" {
  count = local.offline_enabled ? 1 : 0

  name              = "/aws/lambda/cloudradar-offline-contact"
  retention_in_days = var.offline_logs_retention_days
  tags              = var.tags
}

resource "aws_lambda_function" "offline_contact" {
  count = local.offline_enabled ? 1 : 0

  function_name = "cloudradar-offline-contact"
  role          = aws_iam_role.offline_contact_lambda[0].arn
  runtime       = "python3.12"
  handler       = "contact_demo.handler"
  architectures = ["arm64"]
  timeout       = 5
  memory_size   = 128

  filename         = data.archive_file.offline_contact_lambda[0].output_path
  source_code_hash = data.archive_file.offline_contact_lambda[0].output_base64sha256

  environment {
    variables = {
      RATE_LIMIT_TABLE_NAME     = aws_dynamodb_table.offline_rate_limit[0].name
      RECIPIENT_EMAIL           = var.offline_contact_recipient_email
      SENDER_EMAIL              = local.offline_sender_email
      RATE_LIMIT_WINDOW_SECONDS = tostring(var.offline_rate_limit_window_seconds)
      RATE_LIMIT_MAX_HITS       = tostring(var.offline_rate_limit_max_hits)
    }
  }

  depends_on = [aws_cloudwatch_log_group.offline_contact_lambda]
  tags       = var.tags
}

resource "aws_apigatewayv2_api" "offline_contact" {
  count = local.offline_enabled ? 1 : 0

  name          = "cloudradar-offline-contact"
  protocol_type = "HTTP"
}

resource "aws_apigatewayv2_integration" "offline_contact" {
  count = local.offline_enabled ? 1 : 0

  api_id                 = aws_apigatewayv2_api.offline_contact[0].id
  integration_type       = "AWS_PROXY"
  integration_method     = "POST"
  integration_uri        = aws_lambda_function.offline_contact[0].invoke_arn
  payload_format_version = "2.0"
}

resource "aws_apigatewayv2_route" "offline_contact" {
  count = local.offline_enabled ? 1 : 0

  api_id    = aws_apigatewayv2_api.offline_contact[0].id
  route_key = "POST /api/contact-demo"
  target    = "integrations/${aws_apigatewayv2_integration.offline_contact[0].id}"
}

resource "aws_apigatewayv2_stage" "offline_contact" {
  count = local.offline_enabled ? 1 : 0

  api_id      = aws_apigatewayv2_api.offline_contact[0].id
  name        = "$default"
  auto_deploy = true

  default_route_settings {
    throttling_burst_limit = var.offline_api_throttle_burst_limit
    throttling_rate_limit  = var.offline_api_throttle_rate_limit
  }

  tags = var.tags
}

resource "aws_lambda_permission" "offline_contact_apigw" {
  count = local.offline_enabled ? 1 : 0

  statement_id  = "AllowApiGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.offline_contact[0].function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.offline_contact[0].execution_arn}/*/*"
}

resource "aws_acm_certificate" "offline" {
  count = local.offline_enabled ? 1 : 0

  domain_name               = local.dns_zone_name
  subject_alternative_names = [local.offline_domain]
  validation_method         = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = merge(var.tags, { Name = "cloudradar-offline-certificate" })
}

resource "aws_route53_record" "offline_certificate_validation" {
  for_each = toset(local.offline_certificate_validation_domains)

  allow_overwrite = true
  zone_id         = local.zone_id
  name = one([
    for dvo in aws_acm_certificate.offline[0].domain_validation_options : dvo.resource_record_name
    if dvo.domain_name == each.key
  ])
  type = one([
    for dvo in aws_acm_certificate.offline[0].domain_validation_options : dvo.resource_record_type
    if dvo.domain_name == each.key
  ])
  ttl = 60
  records = [one([
    for dvo in aws_acm_certificate.offline[0].domain_validation_options : dvo.resource_record_value
    if dvo.domain_name == each.key
  ])]
}

resource "aws_acm_certificate_validation" "offline" {
  count = local.offline_enabled ? 1 : 0

  certificate_arn = aws_acm_certificate.offline[0].arn
  validation_record_fqdns = [
    for domain in local.offline_certificate_validation_domains :
    aws_route53_record.offline_certificate_validation[domain].fqdn
  ]
}

resource "aws_cloudfront_origin_access_control" "offline_site" {
  count = local.offline_enabled ? 1 : 0

  name                              = "cloudradar-offline-oac"
  description                       = "OAC for CloudRadar offline site bucket"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

data "aws_cloudfront_cache_policy" "caching_optimized" {
  count = local.offline_enabled ? 1 : 0

  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "caching_disabled" {
  count = local.offline_enabled ? 1 : 0

  name = "Managed-CachingDisabled"
}

data "aws_cloudfront_origin_request_policy" "all_viewer_except_host_header" {
  count = local.offline_enabled ? 1 : 0

  name = "Managed-AllViewerExceptHostHeader"
}

resource "aws_cloudfront_distribution" "offline" {
  count = local.offline_enabled ? 1 : 0

  enabled             = true
  is_ipv6_enabled     = true
  comment             = "CloudRadar offline fallback distribution"
  default_root_object = "index.html"
  price_class         = "PriceClass_100"
  aliases             = [local.dns_zone_name, local.offline_domain]

  origin {
    domain_name              = aws_s3_bucket.offline_site[0].bucket_regional_domain_name
    origin_id                = "offline-s3"
    origin_access_control_id = aws_cloudfront_origin_access_control.offline_site[0].id
  }

  origin {
    domain_name = trimprefix(aws_apigatewayv2_api.offline_contact[0].api_endpoint, "https://")
    origin_id   = "offline-contact-api"

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  default_cache_behavior {
    target_origin_id       = "offline-s3"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized[0].id
  }

  ordered_cache_behavior {
    path_pattern             = "/api/*"
    target_origin_id         = "offline-contact-api"
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
    cached_methods           = ["GET", "HEAD"]
    compress                 = true
    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled[0].id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_except_host_header[0].id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.offline[0].certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }

  tags = merge(var.tags, { Name = "cloudradar-offline-cloudfront" })
}

data "aws_iam_policy_document" "offline_bucket_policy" {
  count = local.offline_enabled ? 1 : 0

  statement {
    sid = "AllowCloudFrontReadOnly"

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    actions = ["s3:GetObject"]
    resources = [
      "${aws_s3_bucket.offline_site[0].arn}/*"
    ]

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.offline[0].arn]
    }
  }
}

resource "aws_s3_bucket_policy" "offline_site" {
  count = local.offline_enabled ? 1 : 0

  bucket = aws_s3_bucket.offline_site[0].id
  policy = data.aws_iam_policy_document.offline_bucket_policy[0].json
}

resource "aws_route53_record" "offline_domain_alias" {
  count = local.offline_enabled ? 1 : 0

  zone_id = local.zone_id
  name    = local.offline_domain
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.offline[0].domain_name
    zone_id                = aws_cloudfront_distribution.offline[0].hosted_zone_id
    evaluate_target_health = false
  }

  allow_overwrite = true
}

resource "aws_route53_health_check" "primary_live" {
  count = local.offline_failover_enabled ? 1 : 0

  fqdn              = local.live_domain
  port              = var.offline_primary_health_port
  type              = "HTTPS"
  resource_path     = var.offline_primary_health_path
  request_interval  = 30
  failure_threshold = 3
  measure_latency   = true

  tags = merge(var.tags, { Name = "cloudradar-primary-live-health-check" })
}

resource "aws_route53_record" "main_failover_primary" {
  count = local.offline_failover_enabled ? 1 : 0

  zone_id         = local.zone_id
  name            = local.dns_zone_name
  type            = "A"
  set_identifier  = "primary-live"
  health_check_id = aws_route53_health_check.primary_live[0].id

  failover_routing_policy {
    type = "PRIMARY"
  }

  alias {
    name                   = local.live_domain
    zone_id                = local.zone_id
    evaluate_target_health = false
  }

  allow_overwrite = true
}

resource "aws_route53_record" "main_failover_secondary" {
  count = local.offline_failover_enabled ? 1 : 0

  zone_id        = local.zone_id
  name           = local.dns_zone_name
  type           = "A"
  set_identifier = "secondary-offline"

  failover_routing_policy {
    type = "SECONDARY"
  }

  alias {
    name                   = aws_cloudfront_distribution.offline[0].domain_name
    zone_id                = aws_cloudfront_distribution.offline[0].hosted_zone_id
    evaluate_target_health = false
  }

  allow_overwrite = true
}
