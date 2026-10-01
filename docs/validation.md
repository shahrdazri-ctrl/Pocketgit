# Validation evidence

Release verification checks behavior at several boundaries: domain values and bytes,
packaged CLI processes, filesystem failure paths, native platform jobs, and the actual
JavaFX scene. The [engineering workflow](development-workflow.md) defines acceptance;
this page separates current checks from retained historical investigations.

## Current release gate

The presentation/maintainability revision adds pinned formatting over all 135 Java
sources, removes unused command scaffolding, and updates current engineering
contracts. The verified engine/build source is
[`0b2c656`](https://github.com/shahrdazri-ctrl/Pocketgit/commit/0b2c656756fd818544341a4c07e94ac296ad9fd5).
The accompanying documentation and ownership files do not alter that executable source.

Both release profiles passed on Linux amd64 (Debian 13), OpenJDK 21.0.12.1, and
Maven 3.9.11. Each ran **441 unit cases and 32 packaged integration cases**, with
zero failures, errors, or skips. Core line coverage was **2,646 / 2,827 (93.60%)**,
and configured SpotBugs analysis reported **zero findings**. Formatting changes the
physical source-line denominator, so this percentage is not directly comparable to
older formatting.

```bash
mvn spotless:check
mvn clean verify
mvn -Pgui clean verify
```

In the cloud workspace these commands use environment-specific Maven proxy/mirror
settings; those settings do not change or disable project quality gates.

The exact JavaFX scene step from CI also passed: two branches, two commits, selected
commit metadata, 1,001 changed-file records, and bounded visible cells. Its before/after
metadata manifests were identical. The executable demo passed its branch, exact-byte
restore, and verification assertions. Executable disassembly of 123 unchanged engine
and CLI classes matched the preceding release; the intentional help-description and
unused-command changes were reviewed separately. All 196 local documentation links
resolved, including heading anchors.

The versioned artifacts produced by these checks had the following digests:

```text
e0e3619bbcaeb8a7a8e1fbc586c987c255e56399cec7a99c18236b01cf39436c  pocketgit-1.0.0.jar
6708be0b7678a0de52b1a957a21c7589a9ca1ab07b57175047e40933c299d3ab  pocketgit-gui-1.0.0.jar
```

These identify the tested builds, not a published GitHub release. Rebuilding with a
different JDK or native JavaFX classifier may produce another digest.

| Boundary | Evidence to inspect |
| --- | --- |
| Formatting | Spotless at Maven `validate`; all main/test/GUI Java files included. |
| Unit behavior | `target/surefire-reports/`; canonical formats, safe mutation, algorithms, and input validation. |
| Packaged behavior | `target/failsafe-reports/`; fresh JVMs, exact bytes, process restarts, Unicode, CLI contracts. |
| Core coverage | `target/site/jacoco/`; at least 80% core line coverage. |
| Static analysis | `target/spotbugsXml.xml`; configured high-priority correctness/concurrency findings. |
| Distribution | Versioned JARs and adjacent SHA-256 manifests. |
| Actual viewer | CI's scene step, selected metadata, 1,001 changed files, virtualized cells, unchanged metadata manifests. |

`clean` replaces reports/artifacts from the preceding profile. Preserve artifacts
outside `target/` when comparing or distributing both CLI and GUI releases.

## Regression depth

- Canonical Blob/Tree/Commit bytes, strict JSON, wrong types, corrupt zlib/header/hash,
  missing objects, publication races, and immutable-content reuse.
- Checkout conflicts, file/directory transitions, unrelated user files, mode/directory
  preservation, failure hooks after publication, independent rollback, and retained originals.
- Seeded snapshot round trips, a 1,000-file mixed repository, and a full 64 MiB file
  workflow with `-Xmx96m` in separate CLI JVMs.
- A 2,000-case seeded ignore matcher oracle, adversarial rules, supplementary Unicode,
  case/normalization aliases, Windows device names, and portable ref namespaces.
- Shared history forests, active-path cycles, traversal bounds, bounded text inputs,
  flat LCS matrices, cumulative output limits, late binary markers, and UTF-8 boundaries.
- Terminal/directional controls, Unicode arguments/output, metadata read-only checks,
  optional viewer boundaries, and packaged help/error behavior.

## Native CI provenance

The [workflow](../.github/workflows/ci.yml) runs CLI verification on Ubuntu, Windows,
and macOS, with GUI build/scene validation on Linux. The repository badge links to
current runs. Native results are evidence only for the tested source revision.
Local Linux checks do not establish native Windows/macOS success.

[Run 36782016521](https://github.com/shahrdazri-ctrl/Pocketgit/actions/runs/36782016521)
passed all four jobs for input-safety source revision `312a736`. The
[input-safety report](input-safety-audit.md) records that evidence. Those results
precede the merged dependency updates and current formatting/documentation revision.

## Retained engineering investigations

| Investigation | What it established |
| --- | --- |
| [Safety and portability](audit.md) | User-file recovery, portable paths/root aliases, safe controls, shared graphs, parser updates, and native CI diagnoses. |
| [Resource and recovery](resource-audit.md) | Maximum-size streaming, private edit preparation, directory permissions, staging complexity, aggregate diff limits, and a local 10,000-file workflow. |
| [Repository inputs](input-safety-audit.md) | Bounded Unicode ignores, portable ref namespace scans, directional/path presentation, and observed native runs. |
| [Milestone records](README.md#historical-milestone-records) | Original acceptance results for the completed implementation sequence. |

Historical counts and measurements remain tied to their reports. A local throughput
observation is not a performance threshold, a coverage percentage is not an assertion,
and ordinary rollback is not proof of crash-atomic durability.
