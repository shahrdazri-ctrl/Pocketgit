# PocketGit engineering specification

PocketGit is a local version-control engine implemented in Java 21. This specification
records product scope, engineering constraints, repository invariants, and acceptance
requirements. The ten implementation milestones are complete; subsequent work is
maintenance or a separately specified extension.

Project owner: [shahrdazri-ctrl](https://github.com/shahrdazri-ctrl).

The [repository format](docs/repository-format.md) defines exact encodings. The
[command guides](docs/README.md#current-behavior) define current user-visible
semantics. Changes to either contract require matching regression coverage and
documentation. Historical milestone reports record what was tested at that time;
they do not replace current release verification.

## 1. Product boundary

Version 1.0 supports:

- Repository initialization and discovery from nested working directories.
- Immutable Blob, Tree, and Commit storage with typed SHA-256 IDs.
- Scoped staging, tracked deletions, and a defined root ignore-rule subset.
- Repository-local author configuration and environment overrides.
- Single-parent snapshot commits and independent HEAD/index/working comparisons.
- Parent-linked history, commit inspection, branch creation/listing, and safe switching.
- Staged/unstaged unified text diffs and binary/mode summaries.
- Single-file restore from the index or a historical snapshot.
- Whole-store object/reference/graph/index verification.
- A runnable CLI distribution and optional read-only JavaFX history viewer.

Git compatibility, network transport, remote push/pull, merge creation, detached
checkout, branch deletion, tags, stash/reset, signed PocketGit commits, directory or
staged restore, packfiles, and garbage collection are outside this version's command
surface. The Commit model may contain ordered multiple parents for traversal tests
and later extensions; the creator currently emits zero or one parent.

## 2. Implementation constraints

1. The engine must implement repository behavior directly in Java. It must not invoke
   Git, embed Git, use JGit, or call an external diff/compression program.
2. Java 21+ and Maven 3.9+ are the supported toolchain. Picocli provides CLI parsing,
   Jackson provides structured metadata serialization, and JUnit provides tests.
3. JavaFX is optional and isolated in the `gui` profile. The default CLI must run
   without JavaFX native libraries or a desktop display.
4. Commands parse inputs and present results; domain services own behavior. Services
   accept explicit working paths and return validated domain values.
5. Values exposed by storage/models are immutable. Arrays are copied defensively at
   retaining API boundaries. Streaming APIs document stream ownership and partial output.
6. Clocks, environment lookups, readers, and failure hooks are injectable where needed
   to test determinism, traversal, and ordinary-failure recovery.
7. Required filesystem capabilities must fail explicitly when unavailable. Atomic
   replacement must not silently degrade to a partial-write fallback.
8. Storage and user-facing behavior must work within the documented Windows, macOS,
   and Linux path/encoding rules. Unsupported filesystem features must be reported.

## 3. Repository layout and discovery

The metadata root is `.pocketgit/` inside a working-tree directory. It contains HEAD,
the index, local configuration, objects, branch refs, and movement logs. Checkout and
restore may temporarily create private `edit-*` preparation directories.

Initialization requires an existing directory and resolves its physical root. It
claims new metadata exclusively, preserves existing files, and retains incomplete
metadata for diagnosis. Reinitialization checks required directories and regular
metadata files without silently repairing or replacing invalid state.

Discovery starts at the invocation directory and walks upward. Repository-root
aliases must resolve consistently. Metadata directories and files must be of the
expected type; symlinks, nonregular metadata, and unsafe directory components fail.
Discovery and initialization do not change the process-global working directory.

The source layout separates `cli`, `model`, `repository`, `storage`, `refs`,
`services`, `diff`, `validation`, and `util`. Optional viewer sources live under
`src/gui/java`; unit and packaged-process tests live under `src/test/java`.

## 4. Object invariants

### Identity and envelope

Object IDs are lowercase hexadecimal SHA-256 over:

```text
ASCII(type) + ASCII(" ") + ASCII(decimalPayloadByteLength) + NUL + payload
```

Types are exactly `blob`, `tree`, and `commit`. Decimal lengths are canonical unsigned
values without signs or leading zeros. A header including NUL fits within 128 bytes.
The declared payload length must equal the bytes actually present.

An ID maps to `objects/<first two hex characters>/<remaining 62 characters>`. Object
files contain exactly one zlib stream and no trailing compressed bytes. Compression
settings do not affect the identity of canonical uncompressed content.

Every successful object read/verification must establish the header, configured size
bound, exact length, complete compression stream, and hash. Typed reads must reject
an unexpected type before retaining payload bytes. Prefix resolution accepts only a
unique lowercase hexadecimal prefix across the entire store; it verifies the result.

### Publication and streaming

Publication writes a private complete compressed file, forces its contents, and
creates a non-overwriting hard link at the final ID. Concurrent existing objects are
verified before reuse. Corruption is reported rather than overwritten.

Blob publication, verification, status/snapshot Blob validation, and extraction use
bounded streaming buffers. A streamed write requires exactly the declared input
length. Ordinary failures clean private preparation. Streams remain owned by their
callers. Streaming copy callers keep output private until complete validation succeeds.
Retaining `read` APIs and Tree/Commit semantic decoding may use payload memory.

### Canonical payloads

Blobs store exact bytes, including empty, binary, Unicode, and arbitrary line endings.
Trees contain sorted, unique, portable single-component names and valid type/mode/ID
relationships. Trees can reference Blobs or other Trees, never Commits.

Commit payloads contain a root Tree ID, ordered distinct parent IDs, nonblank author
identity, canonical UTC `Instant` text, and a nonblank message without NUL. Multiline
Unicode messages remain exact in storage.

Tree/Commit payloads are compact UTF-8 JSON with explicit field order and no trailing
newline. Semantic reads reject missing/unknown/null fields, duplicate keys, coercion,
trailing values, invalid models, unsorted entries, and bytes unequal to canonical
reserialization. Dependency updates must preserve canonical fixtures and object IDs.

## 5. Index, working paths, and ignore rules

The index is versioned strict JSON containing sorted repository-relative `/` paths,
Blob IDs, and regular/executable modes. It rejects duplicate paths, ancestor/file
conflicts, unknown versions/fields, malformed IDs/modes, and unsafe names.

Paths must remain within the physical repository root. Parent traversal, absolute
stored paths, metadata paths, backslashes in stored names, symlinks, unsupported file
types, Windows device names, trailing dots/spaces, and separate case/normalization
aliases are rejected. Validation never silently renames stored paths.

The working-tree observer hashes actual bytes and mode. It must detect same-length
edits with restored timestamps. Untracked/ignored content is classified by path
without unnecessarily reading its bytes.

Root `.pocketgitignore` supports literals, `*`, `?`, leading `/`, and trailing `/`
directory rules. Negation, `**`, escapes, and nested rule files are unsupported.
Matching works on Unicode code points and avoids exponential regex backtracking.
The production matcher is checked against an independent seeded oracle.
`.pocketgit/` is always excluded; `.git/` is not implicitly excluded. Already indexed
paths remain tracked after new ignore rules are added.

Scoped staging writes verified Blobs and publishes the next index only after all
required work succeeds. It stages relevant deletions, detects file identity/size/time/
mode changes during reads, and preserves the old index on failure. Valid objects
published before a later index failure may remain unreachable. Sorted descendant
ranges and actual ancestors handle file/directory transitions without scanning the
full index for each candidate.

## 6. Commit and reference contracts

### Configuration and commits

Identity comes from repository-local `user.name` / `user.email`, with each non-null
`POCKETGIT_AUTHOR_NAME` / `POCKETGIT_AUTHOR_EMAIL` overriding its corresponding value.
Invalid or missing identity blocks creation. No default identity is fabricated.
Configuration parsing is strict and bounded; writes use its own cooperative lock.
Older repositories lacking configuration remain readable.

Commit creation reads the index and current attached branch under index/commit locks,
verifies the current snapshot, builds deterministic Trees, writes the Commit, prepares
ref/log replacements, rechecks observed HEAD/ref state, and then publishes.
The initial Commit has no parents; later ordinary Commits have the branch tip as parent.
Unchanged snapshots are reported unless `--allow-empty` is supplied. Working files are
not implicitly staged or rewritten.

Refs and reflog are individually atomic publications. If a ref was published before
another I/O failure, the error must identify that Commit and avoid suggesting that
nothing changed. Reflog is observability, not automatic recovery.

### HEAD and branches

HEAD is a validated symbolic `refs/heads/NAME` reference or a detached full Commit ID.
Read-side history/viewer operations understand detached HEAD; commit/status require
an attached branch, and the CLI does not expose detached checkout.

A branch ref is either empty for an unborn branch or a full Commit ID with optional
final LF. Missing refs are errors where existence is required. Names and parent
components must follow the portable reference rules; namespace aliases, invalid
spellings, symlinks, and file/directory conflicts fail without replacing valid refs.

Branch creation requires a Commit, publishes exclusively, and does not switch HEAD,
rewrite the index, touch working files, or append a movement record. Listing is sorted
and marks the attached current branch using the stored directory-entry spelling.
Reads/updates/switching reject mismatched case while accepting native normalization
of an existing physical directory on normalizing filesystems.

History traversal is iterative and bounded. It follows stored parent order, detects
active-path cycles, and emits shared ancestors once. Readers validate all results
before human-readable output. Multi-root verification/viewer reads traverse shared
history as one forest. `show` verifies the selected Commit without implicitly scanning
all ancestors or rendering a content diff.

## 7. Checkout and restoration contracts

Checkout must compute writes, removals, and all conflicts before editing. Switching
blocks staged changes, affected unstaged modifications/deletions, conflicting
untracked/ignored paths, symlinks, and ancestor/descendant obstructions. Unrelated
working changes survive. File/directory transitions must not consume unrelated files
or directories. There is no force option; same-branch checkout does not rewrite files.

Mutation holds `index.lock` then `commit.lock`. Source/target objects and snapshots are
verified before editing. Exact originals and replacements are streamed into a private
preparation directory; working bytes, attributes, permissions, absent paths, metadata,
and the plan are revalidated before the first mutation.

Complete replacement files are forced and atomically moved. Existing parent directories
needed by replacements remain in place. POSIX executable distinction is restored;
other platforms use regular-file modes.

Ordinary failures attempt working-file and independent index/HEAD/log recovery. The
editor tracks actual mutations rather than rewriting untouched planned paths. Closing
an uncompleted edit attempts rollback. Completion precedes metadata preparation
cleanup so cleanup errors cannot roll files back against already-published HEAD/index.
Incomplete working-file rollback retains exact originals plus a path/hash/permission
manifest and reports the location.

`restore FILE` intentionally discards that file's unstaged edits using the indexed
Blob. `restore --commit ID FILE` uses a verified historical snapshot. Both leave index,
HEAD, refs, and reflog unchanged. Missing parents may be created; directories, unsafe
paths, parent-file obstructions, and missing source entries fail. Restoration supports
one explicitly selected file per invocation.

These are ordinary-failure guarantees, not multi-file crash atomicity. Power loss,
process death, or edits after final revalidation can require manual inspection.
Cooperative locks do not serialize external editors or malicious directory replacement.
The private manifest does not provide previous metadata or complete directory recovery.

## 8. Diff and inspection contracts

Unstaged diff compares index to indexed working paths. Staged diff compares HEAD to
index, including unborn staged additions. Untracked files are excluded. Content and
executable-mode changes are separate dimensions.

Binary classification scans the entire payload for NUL and malformed UTF-8, including
late markers and sequences crossing buffer boundaries. Binary/mode-only comparisons
avoid retaining complete Blob payloads. Text diff preserves final-newline state, trims
shared prefixes/suffixes, computes deterministic LCS edits in a flat bounded matrix,
chooses removal before addition on ties, and emits three-context-line unified hunks.
Input and aggregate output bounds are checked before printing complete results.

Human-readable fields escape terminal and Unicode directional controls. Single-line
labels escape line breaks and tabs; diff/pretty text retains its defined LF/tab layout.
This presentation is not a byte-preserving patch transport. Raw `cat-object` extracts
exact payload bytes without adding a newline. Pretty mode verifies semantic metadata,
rejects binary text, and checks its own size bound before retaining a payload.

Read-only observations recheck relevant metadata and return a retry error if it changed.
Normal failures are concise and omit Java stack traces. Argument-file input is UTF-8;
Windows Java 21 Unicode outside the active code page uses the documented argument-file
route. CLI exit codes are 0 for success/help, 1 for execution errors, and 2 for syntax.

## 9. Integrity verification and resource policy

`verify` inspects HEAD, every branch ref, every stored object including unreachable
ones, direct type-correct references, reachable parent graphs, flattened snapshots,
and index Blob references. It reports deterministic errors without repair/deletion.
It serializes with cooperating writers through index/commit locks. Working-file
content, configuration, ignore rules, and historic reflog semantics are outside this scan.

| Resource | Production limit |
| --- | --- |
| Default object payload / staged file | 64 MiB |
| Index metadata | 16 MiB |
| Author configuration | 1 MiB |
| HEAD metadata | 4 KiB |
| HEAD reflog | 8 MiB |
| Working edit content plus originals | 256 MiB of private disk preparation |
| Snapshot expansion | 256 path components; 100,000 expanded nodes |
| Commit traversal | 100,000 distinct Commits |
| Text diff input per side | 8 MiB; 100,000 lines |
| Changed-region LCS | 4,000,000 cells |
| Diff command aggregate | 32 MiB text input; 250,000 retained hunk lines |
| Pretty inspection | 8 MiB |
| Viewer display | 2,000 Commits; 15 lanes plus condensed overflow |

Limits are resource policy, not substitutes for format/integrity validation. Repeated
DAG expansion counts toward snapshot bounds. Locks are exclusive, non-waiting markers
cleaned on ordinary exit; abandoned markers are removed manually only after confirming
no writer is active and preserving repository state.

## 10. Viewer, distribution, and verification

The JavaFX viewer is read-only and uses the verified service layer. It loads data on a
background worker, orders children before parents, shows refs/metadata/first-parent
file changes, cancels obsolete detail tasks, and virtualizes large lists. Condensed
lanes and display bounds are stated in the UI; omitted connections do not imply absent
parents. Smoke validation exercises real scene selection and unchanged metadata.

The CLI is a self-contained runnable JAR with versioned distribution copy and SHA-256
manifest. The GUI artifact includes platform-specific native libraries and is built
on its target system. Java/Python recording tools are documentation tooling, not
engine/runtime dependencies. Artifact timestamps and plugin versions are pinned for
repeatable packaging; repeated packaging is checked only after full release verification.

Maven verification checks pinned Java formatting, compilation, unit tests, packaged
integration tests, at least 80% core line coverage, selected high-priority correctness/
concurrency findings, and release manifests. CLI/bootstrap/UI are excluded from the
core coverage denominator and tested through separate boundaries.

Tests must include actual byte assertions, canonical fixtures, corruption, failure
injection, rollback obstructions, lock contention, portable inputs, Unicode, independent
algorithm oracles, seeded snapshot round trips, a 1,000-file mixed repository, and a
64 MiB snapshot workflow with `-Xmx96m`. Native CI covers CLI on Ubuntu/Windows/macOS;
Linux covers GUI build and real scene. Reports distinguish observed native results,
local checks, and configured-but-unobserved jobs.

## 11. Completed implementation milestones

| Milestone | Acceptance boundary | Historical evidence |
| --- | --- | --- |
| 1. Foundation | CLI wiring, repository discovery, exclusive initialization, preserved existing metadata. | [Initialization](docs/phase-1-validation.md) |
| 2. Object database | Exact Blob bytes, typed canonical IDs, zlib validation, immutable publication, inspection. | [Object storage](docs/phase-2-validation.md) |
| 3. Staging | Sorted strict index, scoped add/deletions, ignore subset, safe paths, old-index preservation. | [Staging](docs/phase-3-validation.md) |
| 4. Snapshots | Deterministic Trees/Commits, identity, parent links, ref/log publication diagnostics. | [Commits](docs/phase-4-validation.md) |
| 5. State observation | Independent staged/unstaged/untracked categories; actual working hashes and modes. | [Status](docs/phase-5-validation.md) |
| 6. History/references | Bounded parent traversal, prefixes, branch creation/listing, strict HEAD/ref parsing. | [History](docs/phase-6-validation.md) |
| 7. Safe switching | Complete conflict planning, preserved unrelated work, rollback and transitions. | [Checkout](docs/phase-7-validation.md) |
| 8. Diff/restore | Bounded LCS, binary/mode summaries, exact selected-file restoration. | [Diff/restore](docs/phase-8-validation.md) |
| 9. Reliability | Whole-store verification, graph checks, seeded failures/snapshots, coverage/release gates. | [Reliability](docs/phase-9-validation.md) |
| 10. Distribution/viewer | Runnable artifacts, checksums, documentation, real demo, optional scene validation. | [Distribution](docs/phase-10-validation.md) |

Maintenance keeps these contracts intact and adds a regression for confirmed defects.
New features need explicit behavior, storage compatibility, resource/failure analysis,
and acceptance checks. The [development workflow](docs/development-workflow.md)
defines that change process.
