# Runbook: k6 nightly baseline

This runbook explains the lightweight performance baseline workflow used for issue #493.

## Workflow

- File: `.github/workflows/k6-nightly-baseline.yml`
- Triggers:
  - Nightly schedule
  - Manual `workflow_dispatch`
- PR impact: none (not part of PR critical path)

## What is measured

The baseline executes:

- `GET /api/flights?limit=100&sort=lastSeen&order=desc`

with these default k6 settings:

- `vus=10`
- `duration=30s`

Thresholds:

- `http_req_failed < 5%`
- `http_req_duration p95 < 1500ms`
- `checks > 95%`

## Required configuration

For scheduled runs:

- Repository variable `K6_TARGET_BASE_URL` (example format: `https://your-public-edge`)

Optional auth:

- Repository variable `K6_BASIC_AUTH_USER`
- Repository secret `K6_BASIC_AUTH_PASSWORD`

For manual runs (`workflow_dispatch`), you can override:

- `target_base_url`
- `vus`
- `duration`

## Output and artifacts

Each run publishes:

- Step summary (target, VUs, duration, checks rate, failed rate, p95)
- Artifact files:
  - `artifacts/k6-summary-export.json`
  - `artifacts/k6-run.log`

## Quick interpretation

- Green run: baseline is within expected latency/error envelope.
- Failed run:
  1. Open `k6-summary-export.json` and compare `p(95)` and `http_req_failed`.
  2. Check `k6-run.log` for transport/auth/API errors.
  3. Cross-check with `ci-infra` smoke results (`/api/flights` JSON contract).

## Local reproduction

```bash
cd /path/to/CloudRadar
k6 run \
  -e TARGET_BASE_URL="https://your-public-edge" \
  -e K6_VUS="10" \
  -e K6_DURATION="30s" \
  tests/perf/k6-baseline.js
```
