# Contributing to Kagiroi Migration

## Markdown checks

Install the rumdl version pinned in `mise.toml` and `mise.lock`, then run the check:

```sh
mise trust
mise install --locked rumdl
mise run markdown
```

CI runs the same check on every pull request.
