# Phase 3 validation

> Historical implementation record. Current behavior is documented in the [documentation index](README.md); current release checks are in [validation](validation.md).

Reproduce with JDK 21+ and Maven 3.9+:

```bash
mvn clean verify
```

The suite retains earlier phases and adds versioned/sorted immutable index snapshots, deterministic LF serialization, strict JSON schema rejection, path/hash/mode validation, duplicate/ancestor conflicts, size bounds, exclusive locking, and preservation of the old index on failures.

Staging tests cover new/modified/empty/binary files, directories, whole-repository dot scope from nested directories, invocation-relative paths, Unicode/spaces, duplicate additions, scoped file/directory deletions, metadata exclusion, ignore grammar, already tracked ignored files, file/directory transitions, traversal and canceled symlinks, corruption, stale locks, failed Blob writes, oversized files, executable modes, and concurrent additions with explicit retries.

Packaged integration tests execute real Java processes for init/add/cat-object workflows, byte-exact staged content, ignore exclusions, deletion staging, nested invocation, and clean failures without index changes.

## Recorded results

Validated on Linux with Temurin JDK 21.0.12.1 and Maven 3.9.9 on 2026-09-29:

- Final `mvn clean verify`: BUILD SUCCESS.
- 123 unit tests and 9 packaged integration tests passed; 0 failures, 0 errors, 0 skips. Earlier-phase coverage remains included, with `add` now tested as implemented rather than as a placeholder.
- Eight concurrent independent additions, retried on explicit lock contention, preserved all eight entries and their byte-exact Blob contents.
- Independent Python inspection parsed the generated index and checked SHA-256 IDs, zlib object contents, and packaged `cat-object` output against text, empty, binary, and Unicode source files.
- A real nested `add .` invocation staged the full repository, excluded ignored files and metadata, updated modified content, and removed a deleted directory's entries while preserving other paths.
- A rejected traversal left the index unchanged. Manual inspection confirmed LF JSON, no leftover lock/temporary files, and unchanged HEAD/main ref.
- `git diff --check` passed.

Windows/macOS results require actual execution on those platforms. Relevant limitations are documented in [staging](staging.md): the supported ignore subset, default 64 MiB object limit, 16 MiB index limit, filesystem atomic-replacement/hard-link requirements, and crash recovery left to later reliability work.
