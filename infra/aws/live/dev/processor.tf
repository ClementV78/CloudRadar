# Processor: aircraft reference DB configuration (SSM -> ESO -> K8s Secret -> env vars)

resource "aws_ssm_parameter" "processor_aircraft_db_enabled" {
  name        = "/cloudradar/processor/aircraft-db/enabled"
  description = "Processor aircraft reference DB enabled flag (managed by Terraform; consumed via ESO)"
  type        = "String"
  value       = tostring(var.processor_aircraft_db_enabled)
  overwrite   = true

  tags = merge(local.tags, {
    Name = "cloudradar-processor-aircraft-db-enabled"
  })
}

resource "aws_ssm_parameter" "processor_aircraft_db_s3_uri" {
  name        = "/cloudradar/processor/aircraft-db/s3-uri"
  description = "Processor aircraft reference DB S3 URI (managed by Terraform; consumed via ESO)"
  type        = "String"
  value       = var.processor_aircraft_db_s3_uri
  overwrite   = true

  tags = merge(local.tags, {
    Name = "cloudradar-processor-aircraft-db-s3-uri"
  })
}

resource "aws_ssm_parameter" "processor_aircraft_db_sha256" {
  name        = "/cloudradar/processor/aircraft-db/sha256"
  description = "Processor aircraft reference DB artifact SHA256 (managed by Terraform; consumed via ESO)"
  type        = "String"
  value       = var.processor_aircraft_db_sha256
  overwrite   = true

  tags = merge(local.tags, {
    Name = "cloudradar-processor-aircraft-db-sha256"
  })
}

