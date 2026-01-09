 # CloudRadar Agent Guide

## Project Intent
- Portfolio showcase focused on DevOps and cloud architecture, not application complexity.
- Favor speed of delivery, clean structure, and minimal infrastructure cost.
- Frontend should be simple but deliver a "wow" effect.

> 🛠️ **Stack**: Terraform · AWS · k3s · Prometheus/Grafana · React/Leaflet  
> 🎯 **Focus**: DevOps, Cloud Architecture, Observability  
> 💸 **Cloud Cost Awareness**: ✅  
> 📈 **CI/CD & GitHub Actions**: ✅

## Language Policy
- Responses: French.
- Code, docs, comments, and commit/issue text: English.

## Work Style
- Provide a plan for non-trivial tasks.
- Keep changes small, incremental, and easy to review.
- At session start, ask whether to load context from `.codex-context.md`.
- When creating a new issue, note dependencies or relationships to other issues.
- Codex may use `gh` to read issues and the GitHub Project: https://github.com/ClementV78/CloudRadar/issues and https://github.com/users/ClementV78/projects/1/
- Keep `.codex-context.md` updated regularly.
- If a diagram becomes too dense, split it into multiple diagrams instead of increasing visual complexity.
- If splitting changes into multiple commits improves clarity, do so.
- Every new issue must be added to the GitHub Project and placed either in a sprint/iteration or explicitly in Backlog.
- Tech-decision issues must be placed in the Project "Decisions" column.
- Do not commit real emails or account identifiers; use placeholders in the repo.
- Never share credentials (even temporary) in chat or documentation.
- Prefer credential export without writing files to disk.
- For bootstrap tasks, provide both a runbook and a script, and map steps between them.

## Tech Stack Overview
- **Infrastructure as Code**: Terraform (modular, AWS-focused).
- **Orchestration**: k3s (lightweight Kubernetes) on EC2.
- **Cloud Provider**: AWS (cost-aware, IAM-first).
- **Observability**: Prometheus + Grafana (metrics).
- **Backend**: Python (for ingestion/processing services, only if needed).
- **Frontend**: React + Vite + Leaflet (map-based telemetry dashboard).

## Conventions
- Issue/commit format: `type(scope): message`
  - Types: `feat`, `fix`, `chore`, `docs`, `ci`, `refactor`, `test`
- Keep scope short and meaningful (e.g., `infra`, `k8s`, `obs`, `edge`, `app`).
- Use GitHub Project sprints for agile tracking (solo workflow).
- Each issue should include a short DoD section.
- Use milestones `v1-mvp` and `v1.1` aligned with scope labels.
- Sprint Goals live as draft items inside the GitHub Project.
- Use DoR only for issues with external or cross-issue dependencies.

## Commit Conventions
- Use the same `type(scope): message` format as issues.
- Link commits to issues in the body with `Refs #<issue>` or `Fixes #<issue>`.
- Prefer one commit per logical change.

## Branching & Environments
- `main` is the single source of truth (not tied to a specific environment).
- Branch per issue: `feat/1-vpc`, `fix/12-...`, `infra/32-...`.
- Promotion between environments uses IaC variables or `infra/live/*`, not long-lived branches.

## Quality & CI
- Keep tests lightweight but present.
- Add minimal CI checks for infra and app when applicable.
- Prefer lint/format + basic unit tests over heavy suites.

## Documentation Requirements
- Keep `README.md`, GitHub issues, and `docs/architecture/` aligned with decisions.
- Update docs when architecture or infrastructure choices change.
- Update `AGENTS.md` when new global rules or preferences are agreed.
- When asked to export context, follow `docs/context-template.md` exactly and write to `.codex-context.md`.

## Directory Structure
- `infra/` Terraform IaC and modules.
- `k8s/` Kubernetes manifests.
- `src/` Application services (ingester/processor/dashboard).
- `docs/` Architecture, ADRs, and runbooks.

## Decision Records
- Store ADRs in `docs/architecture/decisions/`.
- Add/update ADRs when a non-trivial technical choice is made.
- Naming: `ADR-0001-YYYY-MM-DD-short-title.md` (incremental, zero-padded).
- For each issue completed, check if new ADRs are needed and add them.

## CI/CD Expectations
- GitHub Actions for infra and app workflows.
- Infra: `terraform fmt`, `validate`, and `plan` on PRs.
- App: lint/format + minimal tests on PRs.

## Secrets Management
- No plaintext secrets or credentials in code or state.
- Use AWS SSM Parameter Store or Secrets Manager for runtime secrets.
- Use Terraform `sensitive` outputs and backend encryption.
- Configure `.gitignore` and `.gitattributes` to avoid secrets leakage.

## DevOps/Cloud Value Demonstrated
- IaC-first deployment (Terraform + GitHub Actions).
- k3s on EC2 for lightweight Kubernetes orchestration.
- Observability (Prometheus + Grafana) configured end-to-end.
- Cost-aware choices for infra design.
- Deployment pipeline with infra/app separation.

## Security & Ops
- No plaintext secrets; use least-privilege IAM.
- Prefer secure defaults for networking, storage, and access.
- Cost-awareness is a first-class requirement; justify any higher-cost choices.

## UX Direction
- Frontend is minimal but should feel polished.
- Prioritize clarity and visual impact over feature depth.

## Out of Scope
- No complex backend business logic; only MVP-level ingestion or alerts if needed.
- No user authentication or role management unless required to demonstrate IAM setup.
- No advanced frontend state management (e.g., Redux, Zustand).

## Environment Setup (optional)
- Provide a quick start for local dev when applicable.
- Keep steps minimal and aligned with the current MVP scope.

## Diagram generation (general rules)

When generating or updating diagrams (Mermaid or equivalent):

### General principles
- Prioritize clarity and readability over completeness
- Diagrams must be understandable directly in GitHub
- Avoid visual clutter and overly dense diagrams
- Prefer simple, explicit structures

### Layout rules
- Choose the diagram type that best matches the intent
  (flowchart for flows/dependencies, sequence for interactions, etc.)
- Use a consistent direction (LR or TB) within a diagram
- Group related elements logically (layers, domains, phases)
- Keep groups visually compact

### Styling rules
- Use minimal and sober styling
- Prefer flat colors and thin borders
- Use styles to convey meaning, not decoration
- Avoid relying on advanced Mermaid features not supported by GitHub

### Structural discipline
- Do not invent entities or relationships without strong justification
- Preserve existing semantics when refactoring a diagram
- Reduce crossing edges whenever possible
- If a diagram becomes too complex, split it into multiple diagrams

### Maintenance rules
- When updating an existing diagram:
  - Modify only what is necessary
  - Preserve surrounding documentation
  - Keep naming and structure consistent over time

These rules are persistent and must be applied to all diagrams
unless explicitly overridden.
