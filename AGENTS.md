# CloudRadar Agent Guide

## 1. Project Intent
- Portfolio showcase focused on DevOps and cloud architecture, not application complexity.
- Favor speed of delivery, clean structure, and minimal infrastructure cost.
- Frontend should be simple but deliver a "wow" effect.
- This project supports a DevOps career transition and is used as an interview showcase; prioritize learning outcomes.

## 2. Stack Snapshot
- Stack: Terraform · AWS · k3s · Prometheus/Grafana · React/Leaflet.
- Focus: DevOps, Cloud Architecture, Observability, cost awareness, CI/CD.

## 3. Language Policy
- Responses: French.
- Code, docs, comments, and commit/issue text: English.

## 4. Work Style

### 4.1 Planning & Scope
- Provide a plan for non-trivial tasks.
- At the start of each ticket, summarize expected outcomes and propose a plan before making changes.
- Keep changes small, incremental, and easy to review.
- If splitting changes into multiple commits improves clarity, do so; prefer clean scope separation when reasonable.
- When workflows are hard to test before merge, validate the key steps locally (e.g., Terraform validate/plan).

### Engineering Guardrails (LLM)
- State assumptions explicitly before implementation.
- If ambiguity remains: stop and ask when it is critical (correctness, security, cost, or architecture impact); otherwise proceed with explicit assumptions and report them clearly.
- Implement only requested scope; avoid speculative features, configurability, or abstractions.
- Keep changes surgical: modify only lines directly needed for the request.
- Remove only unused code/imports caused by your own changes; do not clean unrelated legacy code unless asked.
- Define verifiable success criteria and validate them before considering the task done.
- Simplicity self-check (heuristic): ask yourself, "Would a senior engineer call this overcomplicated for this scope?" If yes, simplify.

### 4.2 Context & Documentation Hygiene
- At session start, execute this checklist in order: re-read `AGENTS.md`; load `.codex-context.md` without asking (if present) and confirm recap; review `docs/runbooks/issue-log.md`; review all ADRs in `docs/architecture/decisions/`; review relevant files in `docs/`.
- **`.codex-context.md` maintenance rule**: this is a local session file (never committed; in `.gitignore`). Update it regularly during work (after each major task block) with active branches, current issue/PR, recent decisions, and next steps.
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

- Issues vs PRs semantic clarity:
  - Issues describe problems/requests in future tense with context and DoD; create issues before work starts.
  - PRs describe implemented solutions in past tense; keep 3-5 bullets focused on what changed and why, with issue link.

### 4.5 CI Reproducibility
- CI checks should use example/non-sensitive inputs when a plan/validate requires variables.
- Version lockfiles that pin tool/provider versions to keep CI and local runs consistent.
- In Terraform workflows, use `-var-file` for plan/apply only (validate does not accept it).
- For CI tools that call the GitHub API (e.g., security scanners), provide a GitHub token to avoid rate limits.

### 4.6 Security & Access
- Do not commit real emails or account identifiers; use placeholders in the repo.
- Never share credentials (even temporary) in chat or documentation.
- Never commit sensitive data (non-exhaustive): credentials, tokens, API keys, passwords, real emails, or personal info.
- Do not commit personal URLs or endpoints; use placeholders or SSM/Secrets for private values.
- Prefer credential export without writing files to disk.
- For bootstrap tasks, provide both a runbook and a script, and map steps between them.
- After bootstrap, avoid leaving broad IAM user policies attached; prefer least-privilege roles via OIDC.

### 4.7 FinOps & Cost Awareness
- Prefer free-tier usage for AWS and keep GitHub Actions within free minutes when possible.
- Apply a FinOps mindset: default to free-tier or lowest-cost options, and justify any paid services or upgrades.

### 4.8 Scope & Merge Hygiene
- Do not mix multiple issue scopes in a single branch; split work into separate branches if it happens.
- When asked to make changes outside the current branch scope, explicitly warn and offer to create a dedicated branch before proceeding.
- Do not continue committing on a branch whose PR is already merged/closed; create a new branch and PR for additional changes.
- Keep branches short-lived: sync with `main` at least daily and before opening a PR; if a branch is stale (e.g., >7 days old or far behind `main`), stop and rebase/merge or create a fresh branch and cherry-pick to avoid drift.
- **PR merges**: User merges all PRs except AGENTS.md standalone (agent auto-merges). Agent creates PR and waits for explicit user approval unless it's AGENTS.md-only.
- Use closing keywords to link PRs to issues: prefer `Closes #ID` for features and `Fixes #ID` for bugs so the issue appears in Development.
- Keep destructive workflows (apply/destroy) manual-only with explicit confirmation inputs.

### 4.9 Cloud & DevOps Practices (MVP)

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
- Issue/commit format: `type(scope): message` (types: `feat`, `fix`, `docs`, `ci`, `refactor`, `test`, `skill`).
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
- No external reviewer is required in solo workflow; the agent acts as a sounding board.
- Merged PRs are treated as implicitly approved outcomes.
- This does not override the "request user review before committing" rule above.

### 9.2 AGENTS and Docs Meta Workflow
- Contextual changes (`feat/...`): if an update is linked to a specific feature, modify `AGENTS.md` directly within that feature branch.
- Isolated updates (`docs/...`): use a dedicated branch for general agent maintenance or global rule updates.
- Meta issue for AGENTS.md: track AGENTS-only changes in https://github.com/ClementV78/CloudRadar/issues/55 (no separate issues). Each AGENTS.md PR must reference the meta issue and add a short changelog entry.
- Meta issue for docs: track docs-only changes (README/runbooks/architecture) in https://github.com/ClementV78/CloudRadar/issues/57. Each docs-only PR must reference the meta issue and add a short changelog entry.
- Auto-merge policy (agent-executed): strictly reserved for `AGENTS.md` standalone updates. Codex may apply auto-merge via `gh pr merge` only when all CI checks pass, there are no conflicts, and no other files are modified. Notify the user after merge.
- AGENTS update skill: use `cloudradar-agents-update` when asked to update `AGENTS.md`. Before running the skill, ensure `main` is up to date to avoid stash conflicts.
- Branch cleanup: never delete branches unless explicitly requested by the user.

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
- Ensure runbooks and ADRs link to related issues, and issues link back to those docs.

## 13. Directory Structure
- `infra/` Terraform IaC and modules.
- `k8s/` Kubernetes manifests.
- `src/` Application services (ingester/processor/dashboard).
- `docs/` Architecture, ADRs, and runbooks.

## 14. Decision Records
- Store ADRs in `docs/architecture/decisions/`.
- Add/update ADRs when a non-trivial technical choice is made.
- Naming: `ADR-0001-YYYY-MM-DD-short-title.md` (incremental, zero-padded).
- For each issue completed, check whether new ADRs are needed and add them.

## 15. Secrets Management
- No plaintext secrets or credentials in code or state.
- Use AWS SSM Parameter Store or Secrets Manager for runtime secrets.
- Use Terraform `sensitive` outputs and backend encryption.
- Configure `.gitignore` and `.gitattributes` to avoid secrets leakage.

## 16. Security & Ops
- No plaintext secrets; use least-privilege IAM.
- Prefer secure defaults for networking, storage, and access.
- For sensitive permissions, always revalidate with the user before applying changes.
- Cost-awareness is a first-class requirement; justify any higher-cost choices.

## 17. UX Direction
- Frontend is minimal but should feel polished.
- Prioritize clarity and visual impact over feature depth.

## 18. Out of Scope
- No complex backend business logic; only MVP-level ingestion or alerts if needed.
- No user authentication or role management unless required to demonstrate IAM setup.
- No advanced frontend state management (e.g., Redux, Zustand).

## 19. Diagram Generation Rules

When generating or updating diagrams (Mermaid or equivalent):
- Prioritize clarity and readability over completeness; diagrams must be understandable in GitHub.
- Choose the diagram type that fits the intent (flowchart for dependencies/flows, sequence for interactions, etc.).
- Keep a consistent direction (LR or TB), group related elements logically, and reduce edge crossings.
- Keep styling minimal and meaningful (flat colors, thin borders); avoid unsupported advanced Mermaid features.
- Do not invent entities or relationships without strong justification; preserve existing semantics when refactoring.
- If a diagram becomes dense, split it into multiple diagrams instead of adding visual complexity.
- When updating an existing diagram, modify only what is necessary and preserve surrounding documentation and naming consistency.
- These rules are persistent unless explicitly overridden.
