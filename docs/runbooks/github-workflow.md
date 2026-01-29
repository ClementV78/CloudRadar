# GitHub Workflow & Project Management

> Complete guide to issues, PRs, templates, and automated checks in CloudRadar.

## Table of Contents
1. [Issue Workflow](#issue-workflow)
2. [PR Workflow](#pr-workflow)
3. [Templates Overview](#templates-overview)
4. [Automated Checks](#automated-checks)
5. [Metadata Requirements](#metadata-requirements)
6. [Best Practices](#best-practices)

---

## Issue Workflow

### Creating an Issue

1. Click **"New issue"** on GitHub
2. Choose a template:
   - **Feature Request** → `feat(scope): description`
   - **Bug Report** → `fix(scope): description`
   - **Documentation** → `docs(scope): description`

3. Fill in the required sections (title, problem, solution, DoD)
4. Add metadata **before** or **after** submission:
   - **Assignee** (usually yourself)
   - **Labels** (area + version, e.g., `area/k8s` + `v1-mvp`)
   - **Milestone** (`v1-mvp`, `v1.1`, or `v2`)
   - **Project** (add to **CloudRadar** project board)

### Metadata Verification

When you **create or edit** an issue, a GitHub Actions workflow runs automatically:

```
on:
  issues:
    types: [opened, edited]
```

**What it checks:**
- ✅ Assignee set
- ✅ Labels present
- ✅ Milestone set
- ✅ Project association

**If metadata is missing:**
- 🏷️ Adds label `needs-metadata` (red flag)
- 💬 Posts a helpful comment listing what's missing
- Shows exact format for required metadata

**If metadata is complete:**
- ✅ Removes `needs-metadata` label if present
- Silent success (no comment)

### Example

**Issue #182 created without metadata:**
```
Title: feat(k8s): add prometheus scrape config
Assignee: ❌ Not set
Labels: ❌ Not set
Milestone: ❌ Not set
Project: ❌ Not added
```

**Workflow triggers:**
- Adds label `needs-metadata`
- Posts comment with checklist and instructions

**After you update the issue:**
```
Title: feat(k8s): add prometheus scrape config
Assignee: ✅ @you
Labels: ✅ area/k8s, area/observability, v1-mvp, p1
Milestone: ✅ v1-mvp
Project: ✅ CloudRadar (status: Ready)
```

**Workflow re-runs:**
- Removes `needs-metadata` label
- Issue is now complete and ready to work on

---

## PR Workflow

### Creating a PR

1. Create a branch linked to your issue: `feat/123-short-desc`
2. Make your changes and commit with format: `type(scope): message`
3. Open a PR with the PR template (auto-filled)
4. Fill in:
   - **What Changed** (brief summary)
   - **Why** (link issue with `Closes #XXX` or `Fixes #XXX`)
   - **Files Affected** (list key files)
   - **Notes** (any breaking changes, deployment notes)

5. Ensure:
   - Title follows `type(scope): description`
   - All CI checks pass
   - No conflicts with `main`
   - Documentation updated (if applicable)

### PR Structure

**Keep short:** 3-5 sections max (from AGENTS.md)
- "PR is a progress report, not a deep justification"
- Avoid re-explaining the entire problem from the issue

**Linking issues:**
- Use `Closes #XXX` for features (auto-closes issue on merge)
- Use `Fixes #XXX` for bugs (auto-closes issue on merge)
- Use `Refs #XXX` for related work (no auto-close)

### Example PR

```markdown
## What Changed
- Added Prometheus scrape config for ingester metrics
- Updated kustomization.yaml to include new ConfigMap

## Why
Closes #123

## Files Affected
- k8s/apps/monitoring/prometheus-config.yaml
- k8s/apps/monitoring/kustomization.yaml

## Notes
Config syncs via ArgoCD; no manual apply needed.

---
- [x] Title follows `type(scope): description`
- [x] All CI checks pass
- [x] Documentation updated
```

---

## Templates Overview

### Issue Templates

| Template | Type | Scope | Metadata |
| --- | --- | --- | --- |
| **feature.md** | `feat` | infra, k8s, app, obs, edge, meta, adr, agents | enhancement label |
| **bug.md** | `fix` | (same) | bug label |
| **documentation.md** | `docs` | infra, k8s, app, obs, meta, adr, agents | documentation label |

**Template Structure:**
- `config.yml`: Enforces template selection (disables blank issues)
- Each template includes:
  - Pre-filled title format: `type(scope): description`
  - Guided sections (problem, solution, files, DoD)
  - Final checklist reminding metadata requirements

**Example title in template:**
```
feat(infra|k8s|app|obs|edge|meta|adr|agents): brief description
```
→ User chooses their scope from the list

### PR Template

Single global template: `.github/pull_request_template.md`

**Sections:**
- What Changed
- Why (with `Closes #XXX` / `Fixes #XXX` placeholder)
- Files Affected
- Notes
- Pre-merge checklist

---

## Automated Checks

### GitHub Actions Workflow: `verify-issue-metadata.yml`

**File:** `.github/workflows/verify-issue-metadata.yml`

**Trigger:**
```yaml
on:
  issues:
    types: [opened, edited]
```

**Steps:**
1. Extract issue data (assignees, labels, milestone)
2. Query GraphQL to check project association
3. Determine if metadata is complete
4. If incomplete:
   - Add `needs-metadata` label
   - Post comment with missing items and instructions
5. If complete:
   - Remove `needs-metadata` label (if present)
   - Silent success

**Permissions:** `issues: write` (can modify labels and post comments)

**Example Automated Comment:**
```
⚠️ **Metadata Check**

❌ **Assignee**: Not assigned
✅ Labels: `area/k8s`, `v1-mvp`
❌ **Milestone**: Not set (required: v1-mvp, v1.1, or v2)
❌ **Project**: Not added to CloudRadar project

**Required metadata:**
- Assignee (usually yourself)
- Labels: `area/*` + version (`v1-mvp`, `v1.1`, or `v2`)
- Milestone: `v1-mvp`, `v1.1`, or `v2`
- Project: Add to **CloudRadar** project board

Please update to complete this issue.
```

---

## Metadata Requirements

All issues **must** have (from AGENTS.md section 4.4):

| Field | Required | Notes |
| --- | --- | --- |
| **Assignee** | ✅ | Usually yourself; identifies who works on it |
| **Labels** | ✅ | `area/*` (k8s, infra, app, obs, data, docs) + version label |
| **Milestone** | ✅ Except tooling | `v1-mvp`, `v1.1`, or `v2` |
| **Project** | ✅ | Add to **CloudRadar** project board in UI |
| **Project Status** | ✅ | Set status in project (Ready, In progress, etc.) |

### Label Conventions

**Area labels** (choose one or more):
- `area/k8s` - Kubernetes/container orchestration
- `area/infra` - AWS/Terraform infrastructure
- `area/app` - Application code (Java services)
- `area/observability` - Prometheus/Grafana metrics
- `area/data` - Data pipeline, storage, backups
- `area/docs` - Documentation

**Version labels** (choose one):
- `v1-mvp` - Current MVP sprint
- `v1.1` - Post-MVP enhancements
- `v2` - Future/major refactor

**Priority labels** (optional):
- `p0` - Critical (e.g., security, outage)
- `p1` - High (blocking other work)
- `p2` - Medium
- `p3` - Low

**Type labels**:
- `enhancement` - New feature
- `bug` - Defect
- `documentation` - Docs-only
- `skill` - Tooling/process improvements

**Special labels**:
- `needs-metadata` - Automated flag for incomplete metadata
- `kind/ci` - CI/CD infrastructure
- `kind/maintenance` - Maintenance tasks

---

## Best Practices

### For Issues

✅ **Do:**
- Use templates when creating issues
- Fill all required sections (problem, solution, DoD)
- Add assignee and labels immediately (before or after creation)
- Add milestone matching your sprint
- Add to project board and set status
- Reference related issues/PRs in the description
- Link ADRs or runbooks if relevant

❌ **Don't:**
- Create issues without metadata (workflow will flag it)
- Leave `needs-metadata` label unresolved
- Mix multiple scopes in one issue
- Leave issues unassigned

### For PRs

✅ **Do:**
- Use title format: `type(scope): description`
- Link issue with `Closes #XXX` (features) or `Fixes #XXX` (bugs)
- Keep description short and focused (3-5 bullets)
- List files affected explicitly
- Run local tests/validation before submitting
- Wait for CI to pass before merging

❌ **Don't:**
- Re-explain the entire problem from the issue (reference it instead)
- Create PR without linked issue
- Merge with unresolved conflicts or failed CI
- Mix multiple issue scopes in one PR

### Workflow Rules (from AGENTS.md)

- **One branch per issue:** `type/123-short-title`
- **One commit per logical change:** Use `type(scope): message` format
- **Link commits:** Include `Refs #issue` or `Fixes #issue` in commit body
- **Keep branches short-lived:** Merge daily; rebase if stale (>7 days)
- **No direct push to `main`:** All changes via PR
- **User merges non-AGENTS.md PRs:** Agent creates PR, user approves and merges

---

## Examples

### Complete Issue Flow

```
1. Create issue from "feature" template
   Title: feat(k8s): add prometheus persistent storage

2. Workflow runs (on: opened)
   Result: ⚠️ needs-metadata label added + comment posted

3. You update the issue:
   - Assignee: @yourself
   - Labels: area/k8s, area/observability, v1-mvp, p1
   - Milestone: v1-mvp
   - Project: CloudRadar > Ready

4. Workflow runs (on: edited)
   Result: ✅ needs-metadata label removed

5. You create a branch: git checkout -b feat/180-prometheus-storage

6. You commit: git commit -m "feat(k8s): add prometheus persistent storage
   
   - Updated prometheus-deployment.yaml with PVC
   - Updated kustomization.yaml for new volume
   
   Closes #180"

7. You push and open PR with template auto-filled

8. CI runs, PR passes all checks

9. You merge PR → issue #180 auto-closes
```

### Quick Checklist Before Submitting

**Issue:**
- [ ] Used correct template (feat/fix/docs)
- [ ] Title format correct: `type(scope): description`
- [ ] Problem statement is clear
- [ ] Definition of Done listed
- [ ] Will add metadata immediately after creation

**PR:**
- [ ] Title format correct: `type(scope): description`
- [ ] Linked issue: `Closes #XXX` or `Fixes #XXX`
- [ ] Files affected listed
- [ ] Local tests pass
- [ ] CI will pass (linting, Terraform fmt, etc.)
- [ ] No conflicts with `main`

---

## References

- **AGENTS.md** → [Project conventions and metadata standards](../AGENTS.md)
- **GitHub Projects** → [CloudRadar project board](https://github.com/ClementV78/CloudRadar/projects)
- **ADRs** → [Architecture decisions](../architecture/decisions/)
- **Runbooks** → [Operational procedures](./README.md)
