# Maintaining PocketGit

Project owner and maintainer: [**shahrdazri-ctrl**](https://github.com/shahrdazri-ctrl).
The repository uses a single `main` branch. [CODEOWNERS](.github/CODEOWNERS) assigns
review responsibility to the owner; `.mailmap` normalizes historical identity aliases.

Read the [engineering specification](pocketgit.md), [architecture](docs/architecture.md),
and affected command/format guide before changing behavior. The completed
implementation milestones are historical; new work is maintenance or an explicitly
specified extension. The [engineering workflow](docs/development-workflow.md)
explains requirement, implementation, review, and publication boundaries.

## Build and format

Use JDK 21+ and Maven 3.9+:

```bash
mvn spotless:apply
mvn clean verify
sh examples/demo.sh
```

Spotless pins Google Java Format 1.24.0 with AOSP style over main, test, and GUI Java
sources. The check runs during Maven validation. Use `.editorconfig` for UTF-8/LF and
indentation, and keep `pom.xml` and YAML configuration readable.

For focused behavior checks use `mvn -Dtest=ClassName test`. Full verification also
runs core coverage, packaged Java processes, configured SpotBugs checks, and release
checksums. Shared reader/dependency/viewer changes require
`mvn -Pgui clean verify`; scene validation is defined in [CI](.github/workflows/ci.yml).

## Change review

- Keep parsing/presentation in commands and behavior in services. Engine code must
  not invoke Git, JGit, or the system diff utility.
- State the changed contract and failure/resource boundaries. Use an ADR when a
  storage, consistency, or algorithm tradeoff changes.
- Reproduce confirmed defects and assert observable behavior. For mutations inspect
  exact files, metadata, modes, untouched paths, publication, and recovery.
- Preserve deterministic formats and existing valid IDs. Never weaken integrity
  checks or checkout protection to satisfy a test.
- Update current documentation and retain historical validation provenance. Report
  native CI results only when the corresponding jobs were actually observed.
- Use isolated repositories for CLI experiments. Commit focused, reviewable changes
  with the commands and results actually obtained.

Review dependency updates and upstream security advisories manually. Scheduled
Dependabot version-update PRs are disabled to preserve the main-only workflow.
Jackson updates require canonical Tree/Commit fixture checks; a serialization change
requires an explicit compatibility decision. GitHub Actions are pinned to official
release commit hashes.
