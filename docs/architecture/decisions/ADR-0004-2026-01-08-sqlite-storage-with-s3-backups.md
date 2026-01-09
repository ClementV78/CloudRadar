# ADR-0004: SQLite Storage with S3 Backups

## Status
Accepted

## Context
The MVP needs persistence with minimal cost and operational overhead.

## Decision
Use **SQLite** in the application pod backed by a PVC (EBS), with **daily backups to S3**.

## Consequences
- Low cost and simple deployment.
- Limited concurrency and scaling; acceptable for MVP.
- Clear migration path to managed database (RDS) in later iterations.

## Details
- EBS volume type: gp3.
- Backups run via a Kubernetes CronJob to versioned, encrypted S3.
- Restore supported on environment rebuild.
