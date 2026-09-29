# Repository format — Phase 1

All metadata lives in a real `.pocketgit` directory under the working-tree root. Metadata symlinks are rejected during initialization and reinitialization. Files created in this phase are UTF-8; textual line endings are LF on all platforms.

| Path inside `.pocketgit` | Initial state |
| --- | --- |
| `HEAD` | `ref: refs/heads/main` followed by LF |
| `index` | JSON object with integer `version: 1` and empty array `entries: []`, followed by LF |
| `refs/heads/main` | Empty file representing an unborn branch |
| `objects/` | Empty directory; object formats arrive in Phase 2 |
| `refs/heads/` | Directory for branch references |
| `logs/` | Empty directory; reflog writes arrive in a later phase |

`config` is exposed by the path abstraction but is not created yet. Author identity configuration belongs to the commit phase. Index entries, hashes, object storage, ignores, reflogs, and command locks are not implemented in Phase 1.

## Initialization policy

`init [DIRECTORY]` requires an existing directory; omitted arguments select the current working directory. Relative arguments resolve from the invocation's working directory. Physical paths are used for roots, including when the working directory was reached through a symlink.

Existing metadata is never reset, repaired, or deleted by `init`. A structurally complete existing repository is reported as already initialized. Reinitialization requires real `objects`, `refs`, `refs/heads`, and `logs` directories plus regular `HEAD` and `index` files; it does not parse their contents or require the original `main` branch to remain present. Full integrity checking is deferred to Phase 9.

If `.pocketgit` is a file, symlink, or incomplete directory, initialization fails with a clear error and preserves it. Inspect incomplete metadata before manually moving it aside and retrying; PocketGit does not make that decision for the user.

Initialization claims `.pocketgit` through atomic directory creation before writing new files exclusively. A second initializer never overwrites the first one's metadata; it may encounter incomplete structure while the first is writing and should be retried after the first finishes. Initialization is not a transactional or crash-durable operation yet: an interrupted write may leave partial metadata, and that metadata is deliberately retained. It is never advertised as successfully initialized by the failed invocation.
