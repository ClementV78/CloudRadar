# CloudRadar Agent Guide

## 1. Project Intent
- Portfolio showcase focused on DevOps and cloud architecture, not application complexity.
- Favor speed of delivery, clean structure, and minimal infrastructure cost.
- Frontend should be simple but deliver a "wow" effect.
- This project supports a DevOps career transition and is used as an interview showcase; prioritize learning outcomes.

## 2. Stack Snapshot
> 🛠️ **Stack**: Terraform · AWS · k3s · Prometheus/Grafana · React/Leaflet  
> 🎯 **Focus**: DevOps, Cloud Architecture, Observability  
> 💸 **Cloud Cost Awareness**: ✅  
> 📈 **CI/CD & GitHub Actions**: ✅

## 3. Language Policy
- Responses: French.
- Code, docs, comments, and commit/issue text: English.

## 4. Work Style

### 4.1 Planning & Scope
- Provide a plan for non-trivial tasks.
- At the start of each ticket, summarize expected outcomes and propose a plan before making changes.
- Keep changes small, incremental, and easy to review.
- If splitting changes into multiple commits improves clarity, do so.
- When a clean separation is not too complex or costly, prefer the clean option and keep scopes separated.
- When workflows are hard to test before merge, validate the key steps locally (e.g., Terraform validate/plan).

### 4.2 Context & Documentation Hygiene
- At session start, load context from `.codex-context.md` without asking (if present), then confirm it was loaded and provide a brief recap (recent actions and next planned steps).
- Keep `.codex-context.md` updated regularly.
- Explicitly mark “planned” vs “implemented” in README status sections.

### 4.3 Command Transparency & Learning
- Always show executed commands.
- Explain commands that are new or complex.
- When appropriate, offer to let the user run commands themselves.
- Ensure runbooks and ADRs link to related issues, and issues link back to those docs.
- If there is a conflict between speed of delivery and perfect structure, prefer a reasonable, explicit trade-off and document it briefly.

### 4.4 Project & GitHub Management
- When creating a new issue, note dependencies or relationships to other issues.
- Codex may use `gh` to read issues and the GitHub Project: https://github.com/ClementV78/CloudRadar/issues and https://github.com/users/ClementV78/projects/1/
- Every new issue must be added to the GitHub Project and placed either in a sprint/iteration or explicitly in Backlog.
- Tech-decision issues must be placed in the Project "Decisions" column.
- Keep issue status in the GitHub Project updated as work progresses (Backlog → In progress → In review → Done/Cancelled).
- Use the Project "Ready" column as the queue of next tickets: move one ticket to Ready, start it, move it to In progress, then refresh Ready.
- Always keep the Project backlog board up to date.
- In issues and PRs, include clickable links whenever referencing docs, ADRs, runbooks, or other resources.
- Close GitHub issues once DoD is verified and evidence is recorded.
- Keep GitHub metadata complete: assignees, labels, milestone, project status, reviewers, and cross-links.
- For commits, workflows, issues, PRs, and project items, fill required metadata (assignees, labels, project, milestone, reviewers, and links) consistently.
- Prefer testing workflows on a branch before merging to main.

### 4.5 CI Reproducibility
- CI checks should use example/non-sensitive inputs when a plan/validate requires variables.
- Version lockfiles that pin tool/provider versions to keep CI and local runs consistent.
- In Terraform workflows, use `-var-file` for plan/apply only (validate does not accept it).
- For CI tools that call the GitHub API (e.g., security scanners), provide a GitHub token to avoid rate limits.

### 4.6 Security & Access
- Do not commit real emails or account identifiers; use placeholders in the repo.
- Never share credentials (even temporary) in chat or documentation.
- Never commit sensitive data (non-exhaustive): credentials, tokens, API keys, passwords, real emails, or personal info.
- Prefer credential export without writing files to disk.
- For bootstrap tasks, provide both a runbook and a script, and map steps between them.
- After bootstrap, avoid leaving broad IAM user policies attached; prefer least-privilege roles via OIDC.

### 4.7 FinOps & Cost Awareness
- Prefer free-tier usage for AWS and keep GitHub Actions within free minutes when possible.
- Apply a FinOps mindset: default to free-tier or lowest-cost options, and justify any paid services or upgrades.

### 4.8 Scope & Merge Hygiene
- Do not mix multiple issue scopes in a single branch; split work into separate branches if it happens.
- Do not continue committing on a branch whose PR is already merged/closed; create a new branch and PR for additional changes.
- PR merges are performed by the user, not by Codex.
- Use closing keywords to link PRs to issues: prefer `Closes #ID` for features and `Fixes #ID` for bugs so the issue appears in Development.
- Keep destructive workflows (apply/destroy) manual-only with explicit confirmation inputs.

## 5. Tech Stack Overview
- **Infrastructure as Code**: Terraform (modular, AWS-focused).
- **Orchestration**: k3s (lightweight Kubernetes) on EC2.
- **Cloud Provider**: AWS (cost-aware, IAM-first).
- **Observability**: Prometheus + Grafana (metrics).
- **Backend**: Python (for ingestion/processing services, only if needed).
- **Frontend**: React + Vite + Leaflet (map-based telemetry dashboard).

## 6. Conventions
- Issue/commit format: `type(scope): message`
  - Types: `feat`, `fix`, `docs`, `ci`, `refactor`, `test`
- Keep scope short and meaningful (e.g., `infra`, `k8s`, `obs`, `edge`, `app`).
- Use GitHub Project sprints for agile tracking (solo workflow).
- Each issue should include a short DoD section.
- Use milestones `v1-mvp` and `v1.1` aligned with scope labels.
- Sprint Goals live as draft items inside the GitHub Project.
- Use DoR only for issues with external or cross-issue dependencies.

## 7. Commit Conventions
- Use the same `type(scope): message` format as issues.
- Link commits to issues in the body with `Refs #<issue>` or `Fixes #<issue>`.
- Prefer one commit per logical change.

## 8. Branching & Environments
- `main` is the single source of truth (not tied to a specific environment).
- Branch per issue: `feat/1-vpc`, `fix/12-...`, `infra/32-...`.
- Promotion between environments uses IaC variables or `infra/live/*`, not long-lived branches.

## 9. Git & Contribution Workflow

**Core Rule:** ⛔ **No direct push to `main`.** All changes require a Pull Request.

* **🔗 Contextual Changes (`feat/...`):**
    If an update is linked to a specific feature, modify `AGENTS.md` directly within that feature branch.
* **🛠️ Isolated Updates (`docs/...`):**
    Use a dedicated branch for general agent maintenance or global rule updates.
* **🧭 Meta Issue for AGENTS.md:**
    Track AGENTS-only changes in the meta issue https://github.com/ClementV78/CloudRadar/issues/55 (no separate issues).
    Each AGENTS.md PR must reference the meta issue and add a short changelog entry to it.
* **🧭 Meta Issue for Docs:**
    Track docs-only changes (README/runbooks/architecture) in https://github.com/ClementV78/CloudRadar/issues/57.
    Each docs-only PR must reference the meta issue and add a short changelog entry to it.
* **🚀 Auto-Merge Policy:**
    **Strictly reserved for `AGENTS.md` standalone updates.** Apply **Auto-Merge** right after AGENTS.md update to keep `main` synchronized.
* **🧰 AGENTS Update Skill:**
    Use the `cloudradar-agents-update` skill when asked to update `AGENTS.md`.
* **🧹 Branch Cleanup:**
    Never delete branches unless explicitly requested by the user.

## 10. Quality & CI
- Keep tests lightweight but present.
- Add minimal CI checks for infra and app when applicable.
- Prefer lint/format + basic unit tests over heavy suites.

## 11. CI/CD Expectations
- GitHub Actions for infra and app workflows.
- Infra: `terraform fmt`, `validate`, and `plan` on PRs.
- App: lint/format + minimal tests on PRs.

## 12. Documentation Requirements
- Keep `README.md`, GitHub issues, and `docs/architecture/` aligned with decisions.
- Update docs when architecture or infrastructure choices change.
- Update `AGENTS.md` when new global rules or preferences are agreed.
- When asked to export context, follow `docs/context-template.md` exactly and write to `.codex-context.md`.
- Maintain a single runbook entry point that defines execution order and links to specific runbooks.
- Keep local-only configuration (real values) out of version control; commit example templates instead.

## 13. Directory Structure
- `infra/` Terraform IaC and modules.
- `k8s/` Kubernetes manifests.
- `src/` Application services (ingester/processor/dashboard).
- `docs/` Architecture, ADRs, and runbooks.

## 14. Decision Records
- Store ADRs in `docs/architecture/decisions/`.
- Add/update ADRs when a non-trivial technical choice is made.
- Naming: `ADR-0001-YYYY-MM-DD-short-title.md` (incremental, zero-padded).
- For each issue completed, check if new ADRs are needed and add them.

## 15. Secrets Management
- No plaintext secrets or credentials in code or state.
- Use AWS SSM Parameter Store or Secrets Manager for runtime secrets.
- Use Terraform `sensitive` outputs and backend encryption.
- Configure `.gitignore` and `.gitattributes` to avoid secrets leakage.

## 16. DevOps/Cloud Value Demonstrated
- IaC-first deployment (Terraform + GitHub Actions).
- k3s on EC2 for lightweight Kubernetes orchestration.
- Observability (Prometheus + Grafana) configured end-to-end.
- Cost-aware choices for infra design.
- Deployment pipeline with infra/app separation.

## 17. Security & Ops
- No plaintext secrets; use least-privilege IAM.
- Prefer secure defaults for networking, storage, and access.
- Cost-awareness is a first-class requirement; justify any higher-cost choices.

## 18. UX Direction
- Frontend is minimal but should feel polished.
- Prioritize clarity and visual impact over feature depth.

## 19. Out of Scope
- No complex backend business logic; only MVP-level ingestion or alerts if needed.
- No user authentication or role management unless required to demonstrate IAM setup.
- No advanced frontend state management (e.g., Redux, Zustand).

## 20. Environment Setup (optional)
- Provide a quick start for local dev when applicable.
- Keep steps minimal and aligned with the current MVP scope.

## 21. Diagram generation (general rules)

When generating or updating diagrams (Mermaid or equivalent):

### 21.1 General principles
- Prioritize clarity and readability over completeness
- Diagrams must be understandable directly in GitHub
- Avoid visual clutter and overly dense diagrams
- Prefer simple, explicit structures
- If a diagram becomes too dense, split it into multiple diagrams instead of increasing visual complexity.

### 21.2 Layout rules
- Choose the diagram type that best matches the intent
  (flowchart for flows/dependencies, sequence for interactions, etc.)
- Use a consistent direction (LR or TB) within a diagram
- Group related elements logically (layers, domains, phases)
- Keep groups visually compact

### 21.3 Styling rules
- Use minimal and sober styling
- Prefer flat colors and thin borders
- Use styles to convey meaning, not decoration
- Avoid relying on advanced Mermaid features not supported by GitHub

### 21.4 Structural discipline
- Do not invent entities or relationships without strong justification
- Preserve existing semantics when refactoring a diagram
- Reduce crossing edges whenever possible
- If a diagram becomes too complex, split it into multiple diagrams

### 21.5 Maintenance rules
- When updating an existing diagram:
  - Modify only what is necessary
  - Preserve surrounding documentation
  - Keep naming and structure consistent over time

These rules are persistent and must be applied to all diagrams
unless explicitly overridden.
