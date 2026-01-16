# ADR-0012: Edge Package Access via S3 Gateway Endpoint

## Status
Accepted

## Context
The edge instance runs in a public subnet with egress restricted to private CIDRs.
Cloud-init installs packages from Amazon Linux repositories hosted on S3. With
egress locked down, the instance cannot reach those repositories.

We want to keep edge egress restricted while allowing package installs during
bootstrap.

## Decision
Add a **Gateway VPC Endpoint** for S3 and attach it to the public and private
route tables. Allow edge egress to the **S3 prefix list** on TCP/443.

## Consequences
- Package metadata and downloads stay on the AWS backbone (no Internet egress).
- No extra cost for the S3 gateway endpoint.
- Requires an additional SG egress rule and endpoint resource in Terraform.

## Details
- Service: `com.amazonaws.<region>.s3`.
- Route tables: public + private.
- Edge SG egress: allow TCP/443 to the S3 prefix list.
