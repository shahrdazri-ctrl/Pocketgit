# PocketGit

A Git-inspired version-control engine to be built from scratch in Java 21+.

## Development specification

[pocketgit.md](pocketgit.md) is the authoritative master implementation prompt for this repository. Read it before making development changes and follow its ten phases, engineering rules, acceptance criteria, and definition of done.

The plan covers repository initialization, immutable object storage, staging, commits, status, history and branches, safe checkout, diff and restore, integrity checks, and packaging and documentation.

## Current status

Phase 1 is implemented: Java 21 foundation, Picocli CLI, executable JAR, safe repository initialization, and discovery from nested directories. The remaining commands are explicit placeholders and exit with code 3.

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

## Phase 1 design

- [Architecture](docs/architecture.md)
- [Initialized metadata format and safety policy](docs/repository-format.md)
- [Phase 1 validation](docs/phase-1-validation.md)

The next phase is the content-addressable object database. Staging, commits, status, history, branching, checkout, diff, and restore will follow the master prompt in order.
