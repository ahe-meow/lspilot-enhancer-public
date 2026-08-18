# Documentation Structure

- Status: current
- Updated: 2026-08-18
- Purpose: define the canonical document layout, naming rules, and lifecycle for this repository.

## Directory Map

```text
docs/
|-- README.md
|-- architecture/
|-- assets/
|   `-- icon-source.svg
|-- design/
|   `-- icon.md
|-- project/
|   `-- constraints.md
|-- reference/
|   `-- libxposed-api-102.md
`-- work/
    |-- findings.md
    |-- goal.md
    |-- host-context-truncation-diagnosis.md
    |-- project-handoff.md
    |-- plan.md
    `-- todo.md
```

## Placement

| Directory | Content | Lifetime |
| --- | --- | --- |
| `architecture/` | System boundaries, data flow, invariants, and major component responsibilities | Long-term |
| `assets/` | Editable sources used by documentation or release materials | Long-term |
| `design/` | Visual and interaction decisions | Long-term |
| `project/` | Repository-wide environment, safety, workflow, and release constraints | Long-term |
| `reference/` | Audits and external API facts with a stated baseline | Revalidate when dependencies change |
| `work/` | The single current goal, plan, todo list, findings record, and project handoff | Update in place |

Root-level `README.md`, `CHANGELOG.md`, and `AGENTS.md` remain at the root because tools and users discover them there.

## Naming

- Use lowercase kebab-case for document and asset names: `host-abi-discovery.md`.
- Name files by subject, not author, tool, process, or temporary phase.
- Keep current-state filenames stable and undated. Update `work/goal.md`, `work/plan.md`, `work/todo.md`, `work/findings.md`, and `work/project-handoff.md` in place.
- Use dates inside a document when evidence needs a timestamp. Keep current status in the stable `work/` files; Git history is the archive.
- Use relative repository paths in links and examples unless an environment-specific absolute path is essential.

## Document Format

Every file under `docs/`, except assets and this index, starts with:

```markdown
# Descriptive Title

- Status: current | reference | superseded
- Updated: YYYY-MM-DD
- Purpose: one sentence
```

Use one H1, sentence-case section headings, fenced code blocks with a language, and relative links. Keep one fact in one canonical file; link to it instead of copying it.

## Lifecycle

- Update long-term documents only when architecture, policy, or verified reference facts change.
- Update `work/` whenever the active objective, next action, blocker, or evidence changes.
- Delete obsolete documents after durable facts have moved to their canonical file.
- Track feature requests and defects in GitHub Issues. Keep only the actively executing plan in `docs/work/`.
