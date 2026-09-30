# Developing PocketGit

Read [AGENTS.md](AGENTS.md) and the [master specification](pocketgit.md) before changing engine behavior. PocketGit owns its storage and version-control algorithms; engine code must not invoke Git, JGit, or the system diff utility.

Use JDK 21+ and Maven 3.9+:

```sh
mvn clean verify
sh examples/demo.sh
```

`verify` runs unit tests, the core coverage gate, packaged-process integration tests, SpotBugs, and release checksums. For focused work use `mvn -Dtest=ClassName test`, then run the complete verification before submitting. The optional viewer requires `mvn -Pgui clean verify`; its headless scene fixture is in [CI](.github/workflows/ci.yml).

Keep parsing and rendering in CLI classes and behavior in domain services. Add regression tests that reproduce a defect before fixing it. For mutating operations, test the affected bytes, index, HEAD, refs, modes, rollback, and relevant failure paths. Use temporary repositories rather than the source checkout for CLI experiments.

Object formats must remain deterministic. Document changed path rules, storage behavior, limits, and compatibility in [the format specification](docs/repository-format.md). Never turn off integrity checks or weaken checkout conflict protection to make a test pass.

The repository keeps only `main`. Scheduled Dependabot version-update PRs are disabled so they do not recreate dependency branches. Review Maven and GitHub Actions updates and upstream security advisories manually, then run the full release gates before committing updates to `main`. Jackson updates must also preserve the canonical Tree/Commit byte fixtures and object IDs; a serialization change requires an explicit format decision.

Prefer focused commits with a concrete problem and outcome. Include the commands actually run and their results in a change description; distinguish local Linux checks from remote Windows/macOS CI. Existing [architecture decisions](docs/decisions) explain the tradeoffs to preserve or revisit.
