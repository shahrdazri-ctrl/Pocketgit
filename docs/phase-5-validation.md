# Phase 5 validation

Reproduce with JDK 21+ and Maven 3.9+:

```bash
mvn clean verify
```

Domain tests cover every status category and combined scenarios: new files before the first commit, clean committed snapshots, staged/unstaged changes on the same path, new staged files subsequently deleted, staged deletions subsequently recreated, whole-directory deletions, file/directory replacements, tracked paths under new ignore rules, ignored-only repositories, and empty untracked directories.

Content tests include exact binary/empty/Unicode files, same-size changes with restored timestamps, POSIX executable mode changes before/after staging, large tracked working files beyond the object-write limit, and large untracked files. Streaming hashes are checked against canonical byte-array hashes for all object types, including invalid lengths, read failures, and stream ownership.

Failure tests cover corrupted/missing/mistyped index/HEAD objects, invalid HEAD/refs, malformed ignore rules, and nonignored symlinks. HEAD/ref movement checks fail with retry messages. Status remains usable with held marker locks and unrelated corrupt author/reflog data. Metadata byte inventories prove no object/index/ref/HEAD/config/log or lock changes. Formatter/model tests verify immutable sorted lists, category independence, deterministic output, unborn/clean messages, optional ignored output, strict CLI arguments, and clean errors without partial output.

Packaged integration tests execute actual Java processes for init/add/config/commit/status, nested discovery, every printed change section, default/`--ignored` behavior, read-only inspection, help/argument errors, and corruption failures. Earlier phase tests remain included; the placeholder test now uses `log` because status is implemented.

## Recorded results

Validated on Linux with Temurin JDK 21.0.12.1 and Maven 3.9.9 on 2026-09-30:

- Final `mvn clean verify`: BUILD SUCCESS.
- 243 unit tests and 14 packaged integration tests passed: 0 failures, 0 errors, 0 skips.
- Independent Python inspection used the Unix launcher for init/config/add/commit/status. It independently verified zlib envelopes and SHA-256 IDs, flattened HEAD Trees, parsed the index, and calculated working Blob hashes and modes.
- The independent oracle matched all seven printed categories, including files changed again after staging, a same-length edit with its timestamp restored, a staged deletion, an unstaged deletion, untracked content, and ignored paths.
- Nested `status --ignored` output matched expected classifications; ordinary status suppressed ignored paths. Unborn and clean output matched the documented messages.
- Complete working-tree and metadata byte inventories were identical before/after repeated status calls. A corrupt index returned an error with empty stdout and no further mutations.
- `git diff --cached --check` passed.

Windows/macOS and concurrent filesystem transaction guarantees were not tested or claimed. [Status](status.md) documents read-only behavior, streaming working hashes, ignored subtree summaries, unsupported files, and limits of observing a changing filesystem. Full historical/other-branch integrity auditing remains Phase 9 work.
