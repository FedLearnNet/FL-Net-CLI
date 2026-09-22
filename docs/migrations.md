# JSON migration tasks

The migration runner reads `src/main/resources/migrations/catalog.json`, explains the pending changes,
asks for confirmation, then runs the tasks in order and records the completed revision.

```sh
flnet migrate --kind client --name hospital-a --dry-run
flnet migrate --kind client --name hospital-a
flnet migrate --kind platform --dir /srv/flnet --yes --no-input
```

Fresh deployments record their revision in `.flnet/migrations.json`. Older deployments without that
file need `--from <revision>` once, using the known installed version. The CLI never guesses it.
`--from` must agree with the recorded revision if a state file already exists. “Newest” means the
latest revision in this CLI's catalog; nothing is downloaded.

## The JSON

The current catalog has `baseRevision: 1` and no upgrade tasks yet. A future upgrade can look like this:

```json
{
  "baseRevision": 1,
  "migrations": [
    {
      "kind": "CLIENT",
      "revision": 2,
      "description": "Update the frontend and rename the timeout setting.",
      "tasks": [
        {
          "type": "ENV_SET",
          "path": ".env",
          "description": "Use frontend version 2 the next time containers are started.",
          "key": "FRONTEND_IMAGE",
          "expectedValue": "example/frontend:1",
          "value": "example/frontend:2"
        },
        {
          "type": "ENV_RENAME",
          "path": ".env",
          "description": "Rename the timeout setting, keeping its configured value.",
          "key": "OLD_TIMEOUT",
          "newKey": "REQUEST_TIMEOUT"
        }
      ]
    }
  ]
}
```

Revisions must be consecutive for each kind (`CLIENT` or `PLATFORM`). The runner sorts revisions,
keeps task order, and skips completed revisions. Update the corresponding default bundle and Java
model/env fields in the same release. Keep existing released tasks unchanged.

| Task | Fields and behavior |
| --- | --- |
| `FILE_ADD` | `value` contains the new file text. An existing file must already have identical content. |
| `FILE_REPLACE` | Replace a file whose full text equals `expectedValue` with `value`. |
| `FILE_REMOVE` | Remove a file whose full text equals `expectedValue`. |
| `TEXT_REPLACE` | Replace exactly one `expectedValue` block with `value`, preserving surrounding text. Use for a specific Compose image, service addition/removal or merge. |
| `ENV_ADD` | Add `key=value` only if absent; preserve an existing user value. |
| `ENV_SET` | Change `key` only when its raw value equals `expectedValue`. |
| `ENV_RENAME` | Rename `key` to `newKey`, preserving its value; reject an existing destination key. |
| `ENV_REMOVE` | Remove `key` only when its raw value equals `expectedValue`. |

Every task has a `path` relative to the deployment and a user-facing `description`. Env values include
any literal quotes or inline comments after `=`. Env edits preserve unrelated lines and comments;
duplicate keys, multiline/shell syntax and mixed line endings are rejected. Paths cannot escape the
deployment, follow symlinks, or edit migration metadata, Git internals, known data folders or private
certificate/key files. Never include deployment credentials in JSON or descriptions.

## Execution

Preview computes every task result in memory and checks its preconditions without writing files.
Execution verifies that the files still match the preview, writes the results, then saves the newest
revision last. Env files and revision metadata are written with owner-only file permissions.
`init` refuses a recorded revision incompatible with its bundled configuration. Custom `--bundle-dir`
initializations remain unversioned.

There are no backups, recovery command, journal, checksums, locks, Docker validation or automatic
container restarts. Run one migration at a time. If a write fails or the process is interrupted, some
files may already have changed; inspect and fix them manually before retrying. The revision is only
updated after all configuration writes succeed. Existing backups from the earlier prototype are left
untouched; an old pending journal requires manual inspection before using the simplified runner.

## Code

- `MigrationCatalog` contains the Lombok `@Data` models `Definition` and `Task`.
- `MigrationPlan` holds the preview; `MigrationState` stores kind and completed revision.
- `FLNetMigrationBO` loads the JSON, plans tasks and writes their results.
- `MigrateCommand` handles selection, explanation and confirmation.
- Two small helpers handle text/env edits and constrained file access.
