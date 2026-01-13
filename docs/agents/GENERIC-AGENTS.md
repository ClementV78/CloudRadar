# Generic DevOps Agent Guide

## 1. Project Intent
- Portfolio or production project focused on DevOps and cloud architecture, not application complexity.
- Favor speed of delivery, clean structure, and minimal infrastructure cost.
- Frontend should be simple but deliver a polished, high-impact experience.
- Prioritize learning outcomes and clear operational practices.

## 2. Decision Ownership
- Codex proposes changes, commands, and PRs.
- Final decisions, merges, and applies are always performed by the user.

## 3. Language Policy
- Responses: Local language.
- Code, docs, comments, and commit/issue text: English.

## 4. Work Style

### 4.1 Planning and Scope
- Provide a plan for non-trivial tasks.
- At the start of each ticket, summarize expected outcomes and propose a plan before making changes.
- Keep changes small, incremental, and easy to review.
- If splitting changes into multiple commits improves clarity, do so.
- When workflows are hard to test before merge, validate key steps locally.

### 4.2 Context and Documentation Hygiene
- Explicitly mark "planned" vs "implemented" in README status sections.

### 4.3 Command Transparency and Learning
- Always show executed commands.
- Explain commands that are new or complex.
- When appropriate, offer to let the user run commands themselves.
- Ensure runbooks and ADRs link to related issues, and issues link back to those docs.
- If speed conflicts with structure, prefer a reasonable, explicit trade-off and document it briefly.

### 4.4 Project and GitHub Management
- When creating a new issue, note dependencies or relationships to other issues.
- Every new issue must be added to the GitHub Project and placed in a sprint/iteration or Backlog.
- Keep issue status updated as work progresses (Backlog -> In progress -> In review -> Done/Cancelled).
- Use a "Ready" column as the queue of next tickets and keep it refreshed.
- In issues and PRs, include clickable links when referencing docs, ADRs, runbooks, or resources.
- When creating issues/PRs/comments via `gh`, ensure newlines render correctly (use heredoc or `$'...'`).
- Close GitHub issues once DoD is verified and evidence is recorded.
- Keep GitHub metadata complete: assignees, labels, project status (issues), milestone (except tooling), and cross-links.
- For issues, ensure Assignees, Labels, Project, Project Status, and Milestone are set.
- For PRs, ensure Assignees and Labels are set and use closing keywords so issues appear in Development.

### 4.5 CI Reproducibility
- CI checks should use example/non-sensitive inputs when a plan/validate requires variables.
- Version lockfiles should pin tool/provider versions for consistency.
- In Terraform workflows, use `-var-file` for plan/apply only (validate does not accept it).
- For CI tools that call the GitHub API, provide a GitHub token to avoid rate limits.

### 4.6 Security and Access
- Do not commit real emails or account identifiers; use placeholders.
- Never share credentials (even temporary) in chat or documentation.
- Never commit sensitive data: credentials, tokens, API keys, passwords, real emails, or personal info.
- Prefer credential export without writing files to disk.
- For bootstrap tasks, provide both a runbook and a script, and map steps between them.
- After bootstrap, avoid broad IAM user policies; prefer least-privilege roles via OIDC.

### 4.7 FinOps and Cost Awareness
- Prefer free-tier usage for AWS and keep CI usage within free minutes when possible.
- Default to lowest-cost options and justify any paid services or upgrades.

### 4.8 Scope and Merge Hygiene
- Do not mix multiple issue scopes in a single branch.
- Do not continue committing on a branch whose PR is already merged/closed.
- Use closing keywords to link PRs to issues: `Closes #ID` for features, `Fixes #ID` for bugs.
- Keep destructive workflows (apply/destroy) manual-only with explicit confirmation inputs.

## 6. Conventions
- Issue/commit format: `type(scope): message`
  - Types: `feat`, `fix`, `docs`, `ci`, `refactor`, `test`, `skill`
- Keep scope short and meaningful (e.g., `infra`, `k8s`, `obs`, `edge`, `app`, `meta`, `adr`, `agents`, `agent`).
- Use `docs(meta)` for meta maintenance issues and `docs(adr)` for ADR/decision tracking.
- Each issue should include a short DoD section.
- Use milestones aligned with scope labels (e.g., `v1-mvp`, `v1.1`, `v2`).
- Tooling issues do not require a milestone.

## 6.1 Skills and Agent Rules
- When a change concerns skills, use the `skill` type in titles (e.g., `skill(agents): harden auto-merge`).
- Use `docs(agents)` for agent guide updates (and `docs(agent)` for legacy items when needed).

## 7. Commit Conventions
- Use the same `type(scope): message` format as issues.
- Link commits to issues in the body with `Refs #<issue>` or `Fixes #<issue>`.
- Prefer one commit per logical change.

## 8. Branching and Environments
- `main` is the single source of truth.
- Branch per issue with a consistent prefix.
- Promotion between environments uses IaC variables or environment directories, not long-lived branches.

## 9. Git and Contribution Workflow

**Core Rule:** No direct push to `main`. All changes require a Pull Request.

* **Contextual Changes (`feat/...`):**
    If an update is linked to a specific feature, modify `AGENTS.md` in that feature branch.
* **Isolated Updates (`docs/...`):**
    Use a dedicated branch for general agent maintenance or global rule updates.
* **Documentation Maintenance:**
    Keep agent rules and docs updated when global preferences are agreed.
* **Branch Cleanup:**
    Never delete branches unless explicitly requested by the user.

## 10. Quality and CI
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
- Maintain a single runbook entry point that defines execution order and links to specific runbooks.
- Keep local-only configuration out of version control; commit example templates instead.

## 13. Directory Structure
- `infra/` Terraform IaC and modules.
- `k8s/` Kubernetes manifests.
- `src/` Application services (ingester/processor/dashboard).
- `docs/` Architecture, ADRs, and runbooks.

## 14. Decision Records
- Store ADRs in `docs/architecture/decisions/`.
- Add/update ADRs when a non-trivial technical choice is made.
- Naming: `ADR-0001-YYYY-MM-DD-short-title.md`.
- For each issue completed, check if new ADRs are needed and add them.

## 15. Secrets Management
- No plaintext secrets or credentials in code or state.
- Use AWS SSM Parameter Store or Secrets Manager for runtime secrets.
- Use Terraform `sensitive` outputs and backend encryption.
- Configure `.gitignore` and `.gitattributes` to avoid secrets leakage.

## 16. DevOps/Cloud Value Demonstrated
- IaC-first deployment (Terraform + GitHub Actions).
- k3s on EC2 for lightweight Kubernetes orchestration.
- Observability configured end-to-end.
- Cost-aware choices for infra design.
- Deployment pipeline with infra/app separation.

## 17. Security and Ops
- No plaintext secrets; use least-privilege IAM.
- Prefer secure defaults for networking, storage, and access.
- Cost-awareness is a first-class requirement; justify higher-cost choices.

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

## 21. Diagram Generation (general rules)

### 21.1 General principles
- Prioritize clarity and readability over completeness.
- Diagrams must be understandable directly in GitHub.
- Avoid visual clutter and overly dense diagrams.

### 21.2 Layout rules
- Choose the diagram type that best matches the intent.
- Use a consistent direction (LR or TB) within a diagram.
- Group related elements logically.

### 21.3 Styling rules
- Use minimal styling.
- Use styles to convey meaning, not decoration.

### 21.4 Structural discipline
- Do not invent entities or relationships without strong justification.
- Preserve existing semantics when refactoring a diagram.
- Reduce crossing edges whenever possible.
- Split diagrams if complexity grows.

### 21.5 Maintenance rules
- When updating an existing diagram, modify only what is necessary.
- Preserve surrounding documentation.
- Keep naming and structure consistent over time.
