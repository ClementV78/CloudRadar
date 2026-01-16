# ADR-0011: Edge SSM via VPC Interface Endpoints

## Status
Accepted

## Context
The edge EC2 instance relies on AWS Systems Manager (SSM) for access and on
Parameter Store for basic auth secrets. Egress from the edge security group is
restricted to private subnet CIDRs, which prevents SSM from reaching public
AWS endpoints.

We want to keep egress locked down while restoring SSM connectivity for the
edge instance.

## Decision
Provision VPC interface endpoints in private subnets for:
- `ssm`
- `ec2messages`
- `ssmmessages`
- `kms`

Enable Private DNS on these endpoints and allow inbound TCP/443 from the edge
security group to the endpoint security group.

## Consequences
- SSM traffic stays on the AWS private network.
- No need to open edge egress to the public Internet.
- Adds a small monthly cost for interface endpoints.
- Requires Terraform IAM permissions for VPC endpoint management.

## Details
- Endpoints live in private subnets and use a dedicated security group.
- Edge SG remains restricted to private subnet CIDRs.
- KMS endpoint is required for decrypting SSM parameters via PrivateLink.
