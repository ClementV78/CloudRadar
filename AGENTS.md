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
- At session start, re-read `AGENTS.md` and follow it strictly.
- At session start, load context from `.codex-context.md` without asking (if present), then confirm it was loaded and provide a brief recap (recent actions and next planned steps).
- At session start, review `docs/runbooks/issue-log.md` to avoid repeating known mistakes.
- **`.codex-context.md` maintenance rule**: This is a **local session file** (never committed; in `.gitignore`). Update it **regularly during work** (after each major task block) to track: active branches, current issue/PR, recent decisions, next steps. Enables seamless session continuity and context recovery.
- At session start, review the full ADR list in `docs/architecture/decisions/` to refresh the global architecture context and keep it in mind for decisions and updates.
- At session start, review relevant files in `docs/` to rebuild full project context before making changes.
- Explicitly mark “planned” vs “implemented” in README status sections.
- Update `docs/runbooks/issue-log.md` when a new issue occurs (except minor), when there is new information, or when the issue is resolved/closed.
- Before logging a new issue, check `docs/runbooks/issue-log.md` for similar past incidents to reuse lessons learned.

### 4.3 Command Transparency & Learning
- Always show executed commands.
- Explain commands that are new or complex.
- Stay pedagogical while remaining efficient and execution-focused.
- When appropriate, offer to let the user run commands themselves.
- When sharing logs or metrics, call out the key signals and explicit conclusions.
- When running commands on Amazon Linux minimal/light images, prefer `grep`/`awk` over `rg` (ripgrep may be unavailable).
- When building SSM command strings with `set -u`, escape `$VAR` so expansion happens on the instance, not locally.
- Ensure runbooks and ADRs link to related issues, and issues link back to those docs.
- If there is a conflict between speed of delivery and perfect structure, prefer a reasonable, explicit trade-off and document it briefly.
- Minimize manual changes in AWS Console/UI. Prefer IaC and pipelines: Terraform for AWS infra, ArgoCD for Kubernetes, and CI/CD to execute and audit changes. Any exception must be justified and documented.

### 4.4 Project & GitHub Management

**Metadata Requirements** (AI-actionable checklist):
- **Issues**: Assignees + Labels + Project + Project Status + Milestone (except tooling)
- **PRs**: Assignees + Labels + Closing keywords (`Closes #ID` for feat, `Fixes #ID` for bugs)
- **Meta PRs**: Use `Refs #55` (AGENTS) or `Refs #57` (docs-only) — no closing keywords

**Workflow & Consistency**:
- When creating issues: note dependencies, add to Project (Backlog or sprint), link related ADRs/runbooks
- Tech-decision issues → Project "Decisions" column; others → "Ready" (next queue) or "Backlog"
- Track progress: Backlog → Ready → In progress → In review → Done/Cancelled
- Issue closure: add DoD evidence comment (tests/logs/metrics) before closing
- Include clickable links in issues/PRs when referencing docs, ADRs, runbooks
- GitHub metadata is maintained consistently across all items (avoid "No Status" gotcha)

**Technical Details**:
- Use `gh` CLI to read/create issues; ensure newlines render correctly (heredoc or `$'...'` syntax)
- Do not add PRs to Project board (issue links keep Development clean)
- Do not modify Project Status via API/CLI (re-IDs options; use GitHub UI instead)
- PRs do not require reviewers or Project Status in solo workflow
- Verify labels exist before applying; create if needed
- Use `skill` type for skill updates (e.g., `skill(agents): harden auto-merge`)
- Prefer testing workflows on a branch before merging to main.

### 4.4.1 Issues vs PRs — Semantic Clarity

**Issues** (describe problems/requests, future tense):
- Content: Problem statement, context, why it matters, Definition of Done
- Created before work starts to discuss and plan

**PRs** (describe solutions implemented, past tense):
- Content: What changed, files affected, why (briefly), link to issue
- Keep short: 3-5 bullets max; don't re-explain the entire problem
- PR is a progress report, not a deep justification of the problem

### 4.6 CI Reproducibility
- CI checks should use example/non-sensitive inputs when a plan/validate requires variables.
- Version lockfiles that pin tool/provider versions to keep CI and local runs consistent.
- In Terraform workflows, use `-var-file` for plan/apply only (validate does not accept it).
- For CI tools that call the GitHub API (e.g., security scanners), provide a GitHub token to avoid rate limits.

### 4.7 Security & Access
- Do not commit real emails or account identifiers; use placeholders in the repo.
- Never share credentials (even temporary) in chat or documentation.
- Never commit sensitive data (non-exhaustive): credentials, tokens, API keys, passwords, real emails, or personal info.
- Do not commit personal URLs or endpoints; use placeholders or SSM/Secrets for private values.
- Prefer credential export without writing files to disk.
- For bootstrap tasks, provide both a runbook and a script, and map steps between them.
- After bootstrap, avoid leaving broad IAM user policies attached; prefer least-privilege roles via OIDC.

### 4.8 FinOps & Cost Awareness
- Prefer free-tier usage for AWS and keep GitHub Actions within free minutes when possible.
- Apply a FinOps mindset: default to free-tier or lowest-cost options, and justify any paid services or upgrades.

### 4.9 Scope & Merge Hygiene
- Do not mix multiple issue scopes in a single branch; split work into separate branches if it happens.
- When asked to make changes outside the current branch scope, explicitly warn and offer to create a dedicated branch before proceeding.
- Do not continue committing on a branch whose PR is already merged/closed; create a new branch and PR for additional changes.
- Keep branches short-lived: sync with `main` at least daily and before opening a PR; if a branch is stale (e.g., >7 days old or far behind `main`), stop and rebase/merge or create a fresh branch and cherry-pick to avoid drift.
- **PR merges**: User merges all PRs except AGENTS.md standalone (agent auto-merges). Agent creates PR and waits for explicit user approval unless it's AGENTS.md-only.
- Use closing keywords to link PRs to issues: prefer `Closes #ID` for features and `Fixes #ID` for bugs so the issue appears in Development.
- Keep destructive workflows (apply/destroy) manual-only with explicit confirmation inputs.

### 4.10 Cloud & DevOps Practices (MVP)

**GitOps & IaC as Source of Truth**:
- All infrastructure changes via Git + IaC (Terraform for AWS, Kubernetes manifests for k8s)
- Test before merge: `terraform plan`, `kubectl apply --dry-run=client`
- Idempotent changes: safe to apply multiple times with no side effects

**Observability Endpoints**:
- All services expose `/healthz` (liveness) and `/metrics` (Prometheus) for cluster visibility
- Prometheus + Grafana configured end-to-end; use for debugging and capacity planning

**Cost Awareness**:
- Document resource allocation and cost implications (e.g., PVC size, retention policies)
- Prefer free-tier and lowest-cost options; justify exceptions

## 5. Decided Tech Stack (Decision Context)

**Core Production Stack** (locked, no changes without ADR):
- **IaC**: Terraform (AWS modules, remote state in S3, OIDC for CI/CD)
- **Kubernetes**: k3s on EC2 (lightweight, cost-optimized; see ADR-0002)
- **Ingestion/Processing**: Java 17 + Spring Boot (type-safe, production-proven; see ADR-0014)
- **Event Buffer**: Redis (in-memory queue + aggregates; see ADR-0015)
- **Observability**: Prometheus + Grafana (metrics-first, 7d retention; see ADR-0005)
- **GitOps**: ArgoCD (declarative k8s deployments; see ADR-0013)

**Frontend** (Planned v1.1):
- React 18 + Vite + Leaflet (interactive map, real-time aircraft positions)
- Grafana embeds for cluster health dashboards

**When choosing tools**: Consult the ADR list in `docs/architecture/decisions/` before proposing new technologies. Changes require ADR and user approval.

## 6. Conventions
- Issue/commit format: `type(scope): message`
  - Types: `feat`, `fix`, `docs`, `ci`, `refactor`, `test`, `skill`
- Keep scope short and meaningful (e.g., `infra`, `k8s`, `obs`, `edge`, `app`, `meta`, `adr`, `agents`, `agent`).
- Use `docs(meta)` for meta maintenance issues and `docs(adr)` for ADR/decision tracking.
- Use `docs(agents)` for AGENTS.md updates (and `docs(agent)` for legacy items when needed).
- Use GitHub Project sprints for agile tracking (solo workflow).
- Each issue should include a short DoD section.
- Use milestones `v1-mvp`, `v1.1`, and `v2` aligned with scope labels (`feat(v2)` -> `v2`).
- Tooling issues do not require a milestone.
- Sprint Goals live as draft items inside the GitHub Project.
- GHCR image paths must be lowercase (Docker image refs do not allow uppercase in repository names).

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

### 9.1 Agent vs User Responsibilities

**Agent (Codex) Responsibilities**:
- Create branches, write code/docs, commits
- Validate locally (terraform plan, kubectl dry-run, linting)
- **Always request user review of code/doc changes before committing** — present changes and ask: "Review these changes before I commit?"
- Create PRs with clear descriptions and DoD evidence
- **Auto-merge only**: AGENTS.md standalone updates (no other files, all CI passing, no conflicts)

**User Responsibilities**:
- Review and approve agent-proposed changes before commit (code review, clarity, correctness)
- Merge all non-AGENTS.md PRs manually (feature, fix, infra, app, docs)
- Make final architectural decisions (approve/reject new ADRs, tech choices, structural changes)
- Update GitHub Project status in UI when needed
- Make final call on high-risk changes (terraform apply, destructive operations)

**Agent Must Escalate To User**:
- **Architecture evolution**: if changes affect infra/k8s/app structure, ask: "This modifies the architecture. Should I proceed? Any decisions needed?"
- **New ADR-level decisions**: tool adoptions, major refactors, design patterns
- **Cost implications**: if adding resources or services with cost impact, validate cost assumption first
- **Non-AGENTS.md PRs**: always wait for user approval before merging

**Implicit Approval** (Solo Workflow):
- User approval is implicit for merged PRs (you review your own work via agent-requested review)
- No external reviewer needed; agent acts as sounding board

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
* **🚀 Auto-Merge Policy (Agent-Executed):**
    **Strictly reserved for `AGENTS.md` standalone updates.** Codex (agent) may apply auto-merge directly via `gh pr merge` after creating the PR (conditions: all CI checks passing + no conflicts + no other files modified). User is notified after merge.
* **🧰 AGENTS Update Skill:**
    Use the `cloudradar-agents-update` skill when asked to update `AGENTS.md`.
    Before running the skill, ensure `main` is up to date to avoid stash conflicts.
* **🧹 Branch Cleanup:**
    Never delete branches unless explicitly requested by the user.

## 10. Quality & CI
- Keep tests lightweight but present.
- Add minimal CI checks for infra and app when applicable.
- Prefer lint/format + basic unit tests over heavy suites.
- Continuously improve CI test coverage as the stack grows. When introducing new components or workflows, add or update minimal relevant checks to validate them.

## 11. CI/CD Expectations
- GitHub Actions for infra and app workflows.
- Infra: `terraform fmt`, `validate`, and `plan` on PRs.
- App: lint/format + minimal tests on PRs.

## 12. Documentation Requirements
- Keep `README.md`, GitHub issues, and `docs/architecture/` aligned with decisions.
- Always reference `docs/architecture/infrastructure.md` for infra changes and keep it updated as infra evolves.
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
- For sensitive permissions, always revalidate with the user before applying changes.
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
