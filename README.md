# PocketGit

A Git-inspired version-control engine to be built from scratch in Java 21+.

## Development specification

[pocketgit.md](pocketgit.md) is the authoritative master implementation prompt for this repository. Read it before making development changes and follow its ten phases, engineering rules, acceptance criteria, and definition of done.

The plan covers repository initialization, immutable object storage, staging, commits, status, history and branches, safe checkout, diff and restore, integrity checks, and packaging and documentation.

## Current status

Phases 1–5 are implemented: Java 21 foundation, executable CLI, safe initialization and discovery, immutable SHA-256 object storage, `cat-object` inspection, staging with `add` and `.pocketgitignore`, deterministic snapshot Trees, author configuration, commits with parent links and a reflog, and accurate staged/unstaged/untracked status. History, branching, checkout, diff, and restore remain explicit placeholders that exit with code 3.

## Build and test

Requirements: JDK 21 or newer and Maven 3.9+ on your PATH.

```bash
mvn clean verify
java -jar target/pocketgit.jar --help
java -jar target/pocketgit.jar --version
```

`verify` compiles the project, runs unit tests, builds the self-contained JAR, and runs integration tests against that JAR in real Java processes. `mvn test` runs unit tests only. Build output is in `target/`.

## Initialize a repository

From the source checkout:

```bash
mkdir demo
java -jar target/pocketgit.jar init demo
java -jar target/pocketgit.jar init demo
```

The first invocation initializes `demo/.pocketgit`; the second preserves it and reports that it already exists. `init` without a directory argument uses the current working directory. The target directory must already exist.

For the `pocketgit` command, add this checkout's `bin` directory to your PATH after building. Unix systems use `bin/pocketgit`; Windows uses `bin/pocketgit.cmd`. Both launch the same JAR without changing the working directory. Alternatively, copy `target/pocketgit.jar` anywhere and invoke it with `java -jar`.

```bash
cd demo
pocketgit init
```

CLI exit codes: 0 for success/help, 1 for execution failures, 2 for invalid arguments, and 3 for commands that have not been implemented. Future commands currently accept arguments without validating their eventual syntax and report `Not implemented yet.`

## Inspect stored objects

Use a full lowercase 64-character SHA-256 ID. Discovery works from nested working-tree directories.

```bash
pocketgit cat-object --type HASH
pocketgit cat-object --size HASH
pocketgit cat-object --pretty HASH
pocketgit cat-object HASH > recovered.bin
```

Default output contains only the exact payload bytes, without a header or added newline. `--type` prints `blob`, `tree`, or `commit`; `--size` prints the payload byte length. `--pretty` displays Blob text (rejecting NUL or invalid UTF-8) and formatted, semantically validated Tree/Commit JSON. Choose one inspection mode at a time. Every mode validates the complete stored object, including its SHA-256 ID.

Objects are created by `add` or the Java `ObjectStore` API. See [the object database API](docs/object-database.md) for an example. The default payload limit is 64 MiB. Object writes require filesystem hard-link support, available on typical NTFS, APFS, and ext4 installations; unsupported filesystems fail instead of weakening publication safety.

## Stage files

```bash
pocketgit add README.md
pocketgit add src/
pocketgit add .
```

`add .` stages the whole repository, including from nested directories. Other paths resolve from the invocation directory. Files become byte-exact Blob objects; directory scopes also remove index entries for deleted files. Root `.pocketgitignore` rules exclude untracked files, while already indexed files remain tracked. Symlinks and paths outside the repository are rejected. [Staging documentation](docs/staging.md) defines the supported ignore grammar and safety behavior.

## Commit staged changes

```bash
pocketgit config user.name "Jane Developer"
pocketgit config user.email "jane@example.com"
echo hello > hello.txt
pocketgit add .
pocketgit commit -m "Initial commit"
```

Commits capture the index, including staged content and executable modes, even when working files change after `add`. They leave the index and working files intact. The current branch points to the new Commit, with an empty parent list for the first commit and the previous commit as the next commit's parent. Unchanged snapshots print `Nothing to commit.` and exit successfully; `--allow-empty` creates an explicit empty or unchanged commit. Messages must be nonblank and may contain multiple lines.

`config user.name` and `config user.email` read local settings. `POCKETGIT_AUTHOR_NAME` and `POCKETGIT_AUTHOR_EMAIL` override their respective settings; an absent identity produces an actionable error. See [commits](docs/commits.md) for the formats and publication/recovery behavior.

## Inspect repository status

```bash
pocketgit status
pocketgit status --ignored
```

Status compares HEAD with the index for staged additions/modifications/deletions, and the index with working files for unstaged modifications/deletions. A file can appear in both comparisons when edited again after staging. It also lists untracked files, and `--ignored` shows ignored untracked paths. Tracked files remain visible under new ignore rules. Executable mode changes count as modifications where POSIX permissions are available.

Before the first commit, status reports `No commits yet`. A clean repository reports `nothing to commit, working tree clean`. Status is read-only, hashes tracked file content on every run, and works from nested directories. [Status documentation](docs/status.md) explains classification, ignored directory summaries, error behavior, and concurrent changes.

## Implementation notes

- [Architecture](docs/architecture.md)
- [Initialized metadata format and safety policy](docs/repository-format.md)
- [Phase 1 validation](docs/phase-1-validation.md)
- [Object database design and API](docs/object-database.md)
- [Phase 2 validation](docs/phase-2-validation.md)
- [Staging and ignore rules](docs/staging.md)
- [Phase 3 validation](docs/phase-3-validation.md)
- [Trees, commits, configuration, and ref publication](docs/commits.md)
- [Phase 4 validation](docs/phase-4-validation.md)
- [Status classification and working-tree comparison](docs/status.md)
- [Phase 5 validation](docs/phase-5-validation.md)

The next phase is commit history, references, and branching. Subsequent work follows the master prompt in order.
