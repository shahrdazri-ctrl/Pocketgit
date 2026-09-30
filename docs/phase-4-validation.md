# Phase 4 validation

Reproduce with JDK 21+ and Maven 3.9+:

```bash
mvn clean verify
```

The suite retains prior phases and tests deterministic nested Trees, exact canonical property order and UTF-8 payloads, sorted immutable models, duplicate/unsafe names, type/mode mismatches, Commit round trips, author/parent/message/timestamp preservation, malformed/noncanonical JSON, missing/mistyped objects, deep graphs, and bounded repeated DAG expansion.

Commit tests verify first/second parent links, index-only snapshots despite unstaged edits and untracked files, unchanged index bytes, staged deletions, empty and mode-only snapshots, explicit allow-empty, unchanged rejection, configuration/environment identity, ref movement, and reflog records. Corrupt Blobs/parents/Trees, malformed or missing refs/HEAD, config/index corruption, invalid logs, and held locks preserve the ref/index. Ref preparation fails until the Commit, complete snapshot, and immediate parents are stored and verified.

Metadata tests cover config reload, earlier repositories without config, partial environment overrides, blank identity rejection, unsupported keys, numeric/boolean coercion rejection, size limits, duplicate/unknown fields, bounded/no-follow access, symlinks, prepared replacements, cleanup, publication revalidation, and invalid UTF-8. Packaged integration tests execute separate Java processes for configuration, nested add/commit, two-commit history across restarts, raw/type/pretty inspection, unchanged/allow-empty, and clean CLI errors.

## Recorded results

Validated on Linux with Temurin JDK 21.0.12.1 and Maven 3.9.9 on 2026-09-30 (UTC):

- Final `mvn clean verify`: BUILD SUCCESS.
- 202 unit tests and 11 packaged integration tests passed: 0 failures, 0 errors, 0 skips.
- An independent Python workflow initialized/configured a repository, staged binary/empty/nested executable files, committed a multiline message from a nested directory, verified unstaged/untracked exclusion, then committed a modification and deletion.
- Python independently decompressed each referenced object, verified the single zlib stream, canonical type/byte-length header, SHA-256 ID, payload property order, directory modes, snapshot/index equality, parent links, identity/message, and byte-exact raw `cat-object` output.
- An unchanged commit preserved both the ref and reflog. Manual metadata inspection confirmed symbolic HEAD, two correct old/new reflog records, LF config/index formatting, retained index bytes, and no leftover lock/metadata temporary files.
- `git diff --cached --check` passed.

Windows/macOS and power-loss tests were not run. [Commits](commits.md) documents filesystem requirements, traversal/metadata bounds, cooperative locking, and the interval between ref and reflog publication. The two replacements are individually atomic, not a multi-file transaction; directory durability, recovery automation, and full historical integrity checks remain future work.
