# Phase 1 validation

Reproduce validation with JDK 21+ and Maven 3.9+:

```bash
mvn clean verify
```

The suite covers initialization structure and HEAD/index contents; byte-preserving reinitialization after metadata changes; missing and file targets; incomplete metadata; metadata symlinks; spaces and Unicode; discovery at root and nested paths; nearest-repository selection; filesystem-root termination; invalid markers; normalized paths; help/version; every future-command placeholder; relative init targets; and clean CLI errors.

Packaged integration tests execute `target/pocketgit.jar` in real Java processes for help, version, placeholders, initialization, repeat initialization, and invalid metadata. They check file contents and nested discovery. Symlink unit tests skip only when the host filesystem or permissions do not support creating symlinks.

## Recorded result

Validated on Linux with Temurin JDK 21.0.12.1 and Maven 3.9.9 on 2026-09-29:

- `mvn clean verify`: BUILD SUCCESS; the final `mvn verify` after adding the last symlink discovery case also passed.
- Final suite: 26 unit tests and 3 packaged integration tests; 0 failures, 0 errors, 0 skipped tests.
- Unix launcher executed from a fresh working directory: help exited 0, first init exited 0, repeat init exited 0, and placeholder status exited 3.
- Manual metadata inspection confirmed HEAD's symbolic main reference, an empty main branch file, version-1 empty JSON index, and the required objects/refs/heads/logs directories.
- `git diff --check` passed.

Windows/macOS behavior requires execution on those operating systems; platform-independent code alone is not proof of a passed platform test. Native platform validation and the full CI matrix remain future work. Build-time Shade warnings concern overlapping dependency manifests, license resources, and module descriptors; the packaged classpath application was verified by the integration tests.
