# ADR-0016: Documentation Hub Organization

**Status:** Accepted  
**Date:** 2026-01-28  
**Decider:** User + Agent (documentation review)  

---

## Problem Statement

CloudRadar documentation was highly fragmented:
- **45+ .md files** scattered across the project
- No clear **primary entry point** for finding information
- **Runbooks mixed together** in one flat directory (14 files)
- **ADRs hidden** in `decisions/` folder with no index or cross-references
- **Orphaned files** (notebook.md, project-status.md, context-template.md) with unclear purpose
- **No navigation model** — users didn't know where to look for "deploy X" or "understand Y"

### Impact
- Time wasted searching for documentation
- Duplicated information across files
- New team members (or agents) lost in the structure
- Poor discoverability of architectural decisions (ADRs)

---

## Solution

Introduce a **hub-and-spoke documentation model** with clear hierarchical organization:

### 1. **Central Hub: `docs/README.md`**
   - Primary entry point for all documentation
   - "Where to find what" navigation matrix
   - ADR quick-reference index
   - Cross-reference matrix (e.g., "How do I deploy X?" links to app-arch, runbook, ADR)

### 2. **Runbooks Reorganized into Subfolders**

**From:**
```
docs/runbooks/
├── aws-account-bootstrap.md
├── terraform-backend-bootstrap.md
├── argocd-bootstrap.md
├── ingester.md
├── processor.md
├── health-endpoint.md
├── admin-scale.md
├── redis.md
├── observability.md
├── issue-log.md
├── ci-infra.md
├── ci-app.md
├── infra-outputs.md
└── README.md (execution order)
```

**To:**
```
docs/runbooks/
├── README.md (execution order + links to subfolders)
├── bootstrap/
│   ├── aws-account-bootstrap.md
│   ├── terraform-backend-bootstrap.md
│   └── argocd-bootstrap.md
├── operations/
│   ├── ingester.md
│   ├── processor.md
│   ├── health-endpoint.md
│   ├── admin-scale.md
│   ├── redis.md
│   └── observability.md
├── ci-cd/
│   ├── ci-infra.md
│   ├── ci-app.md
│   └── infra-outputs.md
└── troubleshooting/
    └── issue-log.md
```

**Benefits:**
- Clear mental model: "bootstrap", "operations", "ci-cd", "troubleshooting"
- Grouped runbooks by phase and responsibility
- Reduced cognitive load when searching for a task

### 3. **Root README.md Updated**
   - Added "Where to Find Information" navigation table
   - Links to Documentation Hub as primary entry point
   - Quick reference for common queries

### 4. **ADRs Remain in `decisions/` but Indexed**
   - `docs/README.md` includes complete ADR index with titles and key decisions
   - Cross-references from relevant runbooks and architecture docs

### 5. **Clarified File Purpose**
   - `AGENTS.md` stays at root (agent guidance, not user docs)
   - `.codex-context.md` in `.gitignore` (local session file, not in repo)
   - `docs/context-template.md` left in place (bootstrap template)
   - Other orphaned files cleaned up or relocated

---

## Rationale

**Why a hub-and-spoke model?**
- **Single entry point** (`docs/README.md`) reduces decision paralysis
- **Runbook subfolders** mirror real-world phases (bootstrap → operations)
- **ADR index** makes decisions discoverable without hunting
- **Cross-references** enable users to jump from "how do I deploy X?" → architecture → ADR → cost implications

**Why reorganize runbooks instead of leaving flat?**
- 14 files in one folder is hard to navigate
- Logical grouping (bootstrap vs ops) reflects user workflows
- Subfolders are a proven pattern (bootstrap scripts, deployment stagesadded in next iteration)

**Why not a single monolithic doc?**
- Too large to maintain, difficult to update independently
- Hub allows modular growth (add new runbooks without rewriting others)
- Faster navigation and focused reading

---

## Decision

1. ✅ Create `docs/README.md` as the documentation hub
2. ✅ Reorganize runbooks into `bootstrap/`, `operations/`, `ci-cd/`, `troubleshooting/` subfolders
3. ✅ Update root `README.md` with navigation table and link to hub
4. ✅ Create ADR index in `docs/README.md`
5. ⏳ Agents: Use `docs/README.md` as the primary reference when directing users to docs

---

## Implementation

### Phase 1: Create Hub (Done)
- ✅ `docs/README.md` with full navigation, ADR index, and cross-references
- ✅ Updated `root README.md` with "Where to Find Information" table

### Phase 2: Reorganize Runbooks (Done)
- ✅ Created subfolders: `bootstrap/`, `operations/`, `ci-cd/`, `troubleshooting/`
- ✅ Moved files into appropriate folders
- ✅ Updated `docs/runbooks/README.md` with new structure and folder-based navigation

### Phase 3: Maintain & Evolve
- When adding new runbooks, place in appropriate subfolder
- Update hub index if adding major documentation sections
- Link new ADRs to hub index
- Review hub annually (or when docs exceed 50+ files)

---

## Alternative Considered

**Single monolithic docs file:**
- Pro: Everything in one place
- Con: Unmaintainable, hard to update, poor for navigation
- Decision: Rejected (hub model is more scalable)

---

## Consequences

**Positive:**
- Clear entry point for all documentation
- Runbooks easier to find and navigate
- ADRs discoverable and cross-referenced
- Better onboarding for new team members or agents

**Negative:**
- Small: One more level of folder nesting for runbooks (mitigated by hub index)
- Need to maintain cross-references as docs evolve

**Risk Mitigation:**
- Hub index is auto-maintained by agent (links updated with content)
- Runbook cross-links remain stable (subfolder structure unlikely to change)

---

## Related Decisions

- **ADR-0006 & ADR-0010**: Documentation and IaC reflect decision transparency
- **AGENTS.md § 4.2**: Agents must keep documentation synchronized with code

---

## Follow-Up

- Monitor if additional organization needed (e.g., per-service doc folders under `docs/services/`)
- Consider auto-generating index from ADR headers (future automation)
- Review after v2 release and apply learnings to expanded docs

---

**Decision adopted by:** User (structural decision) + Agent (implementation)  
**Last updated:** 2026-01-28
