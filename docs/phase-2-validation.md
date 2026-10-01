# Phase 2 validation

> Historical implementation record. Current behavior is documented in the [documentation index](README.md); current release checks are in [validation](validation.md).

Reproduce with JDK 21+ and Maven 3.9+:

```bash
mvn clean verify
```

Tests retain all Phase 1 cases and add independent SHA-256 golden vectors; type separation; multibyte byte-length headers; immutable byte-array models; empty, binary, and 1 MiB Blob round trips; Tree/Commit envelopes; restart persistence; deterministic paths; duplicate preservation; missing/malformed IDs; invalid headers and lengths; malformed, truncated, trailing, and concatenated zlib streams; hash mismatches; refusal to repair existing corrupt objects; bounded inflation; symlink rejection; concurrent publication; and temporary-file cleanup.

CLI tests cover repository discovery from nested directories, mutually exclusive modes, text/binary handling, exact raw output, clean errors, and absence of ref/index mutations. Packaged integration tests run real Java processes and compare binary stdout byte for byte.

## Recorded results

Validated on Linux with Temurin JDK 21.0.12.1 and Maven 3.9.9 on 2026-09-29:

- `mvn clean verify`: BUILD SUCCESS.
- 70 unit tests and 6 packaged integration tests passed, with 0 failures, 0 errors, and 0 skips. All 29 Phase 1 tests remain included.
- Twelve concurrent writers of the same 1 MiB payload produced one verified stored object and no remaining temporary files.
- Independent Python SHA-256 and zlib decoding agreed with Java-written empty, text, and 1 MiB binary objects. Their raw payloads were recovered byte for byte through the packaged CLI from a nested Unicode path.
- A Tree envelope independently encoded and compressed by Python was successfully inspected by the packaged Java CLI.
- Manual inspection confirmed that object operations left the staging index and main ref unchanged and removed temporary files.
- `git diff --check` passed.

Windows/macOS execution is not available in this environment; those platform results must not be inferred from Linux success. Writes require hard-link support, and whole-object reads/writes use a configurable payload limit (64 MiB by default). Tree/Commit semantics, staging, and abandoned temporary-file recovery remain future phases.
