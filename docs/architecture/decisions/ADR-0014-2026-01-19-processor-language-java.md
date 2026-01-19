# ADR-0014: Processor Service Language (Java/Spring Boot)

## Architecture Decision Summary
The processor service will be implemented in **Java (Spring Boot)** to demonstrate real-world enterprise deployment practices on Kubernetes, while keeping the service minimal and aligned with the MVP scope.

---

## Status
Accepted

---

## Links
- Related issue: https://github.com/ClementV78/CloudRadar/issues/5

---

## Context
The processor service is part of the MVP pipeline (ingest -> process -> store). The choice must balance enterprise signal, delivery simplicity, and resource footprint.

---

## Options Considered

### Option A - Java (Spring Boot)
- Widely used in enterprise environments; strong metrics/health ecosystem.
- Heavier baseline memory and slower startup than lighter runtimes.

---

### Option B - Python (FastAPI or Flask)
- Fast to implement with lower default footprint.
- Less representative of enterprise Java stacks.

---

### Option C - Go
- Very small images, fast startup, low memory usage.
- Less common in Java-centric enterprise stacks.

---

## Decision
**Option A (Java/Spring Boot)** is selected.

The processor will be a minimal Spring Boot service, designed to keep the footprint reasonable while demonstrating:
- JVM containerization practices,
- Kubernetes readiness/liveness probes,
- metrics and logging integration.

---

## Consequences
- Higher memory baseline than Python/Go; set explicit resource limits.
- Larger images and slower startup; tune readiness probes accordingly.
- Better alignment with enterprise deployment expectations.

---

## Implementation Notes
- Keep dependencies minimal (web + actuator only).
- Use a slim JRE base image (e.g., Eclipse Temurin JRE).
- Define CPU/memory requests and limits in Kubernetes manifests.
- Expose health and metrics endpoints for Prometheus.
