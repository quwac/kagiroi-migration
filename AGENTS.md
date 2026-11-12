# Working on Kagiroi Migration

## Branches and documentation

- Use ASCII branch names beginning with `feature/` or `hotfix/`.
  Rename a nonconforming working branch before making changes.
- Explanatory documents, designs, and specifications describe current behavior
  and guarantees. Do not include implementation history or a chronology of changes.
- When documentation and existing code disagree, follow the code and list every
  discrepancy found in the final response.
- Put user-facing behavior in `README.md`, contributor tooling and maintenance
  instructions in `CONTRIBUTING.md`. Refer to these documents rather than
  duplicating their contents here. Do not restate values from configuration files.
- Update relevant documentation when changing public APIs, artifact metadata,
  CLI configuration, or dialect support. Keep artifact metadata changes consistent
  with `migration-artifact-v1.schema.json`.
- `AGENTS_ja.md` is a local Japanese translation for the repository owner.
  Update it alongside this file when it exists. Keep it out of Git using the file
  reported by `git rev-parse --git-path info/exclude`; do not modify `.gitignore`
  for the translation.

## Design constraints

- Keep schema generation, verification, and database execution separate.
  Do not introduce live database or Git-history dependencies into generation,
  or infer schema deltas from handwritten SQL.
- Preserve deterministic schema encoding, file-level delta replay, and step hashes when changing artifact handling.
- Preserve dialect semantics and artifact transaction boundaries. Reject
  unsupported operations rather than silently substituting different behavior.
- Preserve migration locking and history state transitions. Do not automatically
  retry incomplete steps or treat history repair as database rollback.
- Keep shared logic independent of host APIs. Put provider-specific connection
  and lock behavior in adapters, and preserve all configured targets.

## Verification and dependencies

- Follow `CONTRIBUTING.md` for setup and checks, and `hk.pkl` for lint and
  formatting rules. Do not duplicate those rules here.
- Run tests appropriate to the change: core tests for schema, artifact, parser,
  and execution changes; CLI tests for CLI changes; plugin tests for Gradle task
  changes; affected adapter contracts for provider and locking changes.
- Use disposable databases for contracts that execute SQL or repair history.
  Report only checks actually performed, distinguishing local checks, IDE sync,
  remote CI, and remote database contracts.
- Do not disable strict dependency verification or regenerate expected hashes to
  make a normal build pass. Verify artifact provenance before accepting new hashes.
- Regenerate lockfiles with their owning tools; do not edit generated locks by hand.
- Keep real credentials in environment variables or locally excluded settings.
  Use synthetic credentials in tests and do not include real secrets in reports.
