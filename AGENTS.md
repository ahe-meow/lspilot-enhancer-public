# Repository Instructions

## Required Context

Before changing this repository, read:

1. `docs/work/goal.md`
2. `docs/work/plan.md`
3. `docs/work/todo.md`
4. `docs/work/findings.md`
5. `docs/work/project-handoff.md`
6. `docs/project/constraints.md`

For documentation changes, also read `docs/README.md`. For implementation work, read the relevant file under `docs/architecture/`, `docs/design/`, or `docs/reference/`.

## Working Rules

- Treat source, tests, and build configuration as the implementation source of truth. Documentation records intent, rationale, current state, and evidence.
- Keep one current goal, plan, todo list, and findings record. Update those files in place when the state changes; use Git history for older snapshots.
- Preserve unrelated dirty and untracked files. Diagnose before any destructive Git or device operation.
- Follow `docs/project/constraints.md` for Android, Termux, PRoot, build, APK, root, and runtime-verification requirements.
- Keep documentation paths and names compliant with `docs/README.md`.

## Completion

- Run checks proportional to the changed behavior.
- Mark work complete only with current evidence from the exact source or artifact under test.
- Record a new blocker or durable discovery in `docs/work/findings.md`, then update `docs/work/plan.md` and `docs/work/todo.md` if it changes the next action.
