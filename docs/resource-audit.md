# Resource usage and recovery audit

This follow-up audits the implementation at `ae3d746`, including the preceding
[safety and portability audit](audit.md). It preserves those fixes and the completed
ten-phase engine. Object IDs, canonical payload bytes, and existing repository
formats remain compatible; private edit preparation is documented separately.

## Confirmed problems and changes

| Problem | Evidence and resulting behavior |
| --- | --- |
| A successful checkout could change private directory permissions. | A real CLI switch changed a mode-0700 directory to 0755 under umask 022. A failing regression confirmed changed permissions; the final test also checks parent identity. Required existing parent directories now remain in place, including during same-path atomic replacement. |
| The advertised maximum Blob could exhaust the JVM heap. | Staging a valid 64 MiB file with `-Xmx96m` produced `OutOfMemoryError`. Blob publication now streams hashing/compression; validation, extraction, status, Tree construction/traversal, and integrity scanning avoid retaining Blob payloads. Typed Tree/Commit reads reject a Blob before allocating it, and prefix resolution uses streaming validation. |
| Checkout/restore preparation retained several whole-file copies. | Originals and replacements now spool into private disk preparation with the existing 256 MiB combined content budget. Closing an uncompleted editor attempts recovery. Successful completion or recovery cleans preparation; obstructed working-file rollback retains exact originals and a path/hash/permission manifest and reports its location. |
| Editor completion needed to precede metadata cleanup. | A failing regression caught an edge case in the new editor lifecycle: obstructed temporary metadata cleanup after publication produced old working bytes with new HEAD/index. Checkout now completes the editor before metadata cleanup, reports completed publication on cleanup failure, and keeps files consistent with HEAD/index. |
| Staging scanned the entire index for every candidate. | The full-index conflict `removeIf` made unchanged restaging quadratic in file count. Sorted descendant ranges and actual path ancestors now handle file/directory transitions without that repeated scan. Existing transition, ignore, collision, and publication regressions remain required. |
| Per-file diff bounds did not bound a command or binary classification. | Full-content streaming probes detect late NUL/malformed UTF-8 and chunk-boundary Unicode, retaining a bounded prefix. Binary and mode-only diffs avoid payload arrays. Commands also cap combined text at 32 MiB and output at 250,000 hunk lines, validate results before printing, and stream formatting. Regressions exceed each cumulative bound using individually valid files. |
| Pretty object inspection could emit terminal control sequences. | A failing test emitted ESC/OSC controls from a Blob. Pretty output now escapes controls, retains LF/tab layout, and enforces an 8 MiB limit before reading a payload into memory. Raw extraction remains byte-exact. |

Audited code paths were formatted for readability. The [streaming/preparation
decision](decisions/007-streaming-and-edit-preparation.md), API examples, storage
specification, command guides, README, and recovery instructions describe the final
behavior and its limits.

## Validation

Validated engine revision: `b180010`. Both final profiles passed in this Linux
cloud workspace using JDK 21.0.12.1 and
Maven 3.9.11. Maven used the environment's proxy/mirror settings; no project quality
check was disabled.

| Check | Result |
| --- | --- |
| CLI `mvn -B -ntp clean verify` | 425 unit tests and 28 packaged integration tests; zero failures/errors/skips. |
| GUI `mvn -B -ntp -Pgui clean verify` | 425 unit tests and 28 packaged integration tests; zero failures/errors/skips. |
| Core JaCoCo line coverage | 2,350 / 2,504 lines = 93.85%; the 80% gate passed in both profiles. |
| Configured SpotBugs checks | Zero findings and zero analysis errors in both profiles. |
| Versioned artifacts | CLI and GUI SHA-256 manifests passed verification. |
| Actual CI JavaFX scene script | Two branches, two commits, selected metadata, and 1,001 virtualized changed-file rows; before/after metadata manifests matched. |
| Actual executable demo | Branch/checkout/diff/restore workflow completed and ended with clean status and `Repository OK`. |
| Successful checkout permissions | A real packaged switch preserved mode 0710 and the existing parent directory's inode. |

The [captured JavaFX scene](screenshots/resource-history-viewer.png) was also
visually inspected after the automated selection and detail assertions.

The baseline at `ae3d746` passed 406 unit tests and 27 packaged integration tests.
The new regressions add 19 unit cases and one integration workflow.

The added packaged maximum-size workflow uses separate JVM processes with `-Xmx96m` and checks
stage/restage, commit, binary diff, exact restore, deletion/commit, checkout, clean
status, repository verification, size inspection, and raw extraction. It also checks
that large pretty output and Blob-poisoned history refs fail without heap errors,
partial human-readable results, working-file changes, or leaked preparation/locks.

A separate packaged scale workflow created 10,000 small text files in ten
subdirectories, with 100 distinct contents, using `-Xmx96m` for every CLI process.
It completed first staging, unchanged restaging, commit, clean status, and integrity
verification. Observed seconds, including JVM startup: first stage 2.268, restage
2.460, commit 1.148, status 1.651, verify 1.032. These are local observations while
other release checks ran, not performance thresholds or a measured before/after ratio.

## Resource and recovery boundaries

The streaming workflow is evidence for the supported maximum-size Blob, not a
constant-memory guarantee for arbitrary repository metadata. Tree/Commit semantic
decoding and embedding `read`/`readBlob` APIs retain payloads and defensive copies.
Text diff and pretty inspection have explicit bounds; raw extraction and byte-exact
restore support the existing 64 MiB Blob limit.

Private preparation consumes disk space and additional I/O. Its manifest assists
manual working-file recovery; it is not a durable transaction log and does not
restore previous HEAD/index/reflog or complete directory metadata. Process death,
power loss, and external edits after revalidation remain documented limits. Follow
the [recovery guide](integrity.md#private-preparation-and-working-file-recovery)
before manually moving or deleting retained files.

Validation performed in this cloud workspace is Linux evidence. The configured CI
matrix runs CLI checks on Ubuntu, macOS, and Windows and the GUI scene on Linux;
earlier native CI results in the previous audit apply to those earlier revisions.
The GitHub Actions API returned HTTP 403 during that audit, so remote results were
not observed at the time. A subsequent review confirmed that [run 36772066984](https://github.com/shahrdazri-ctrl/Pocketgit/actions/runs/36772066984)
passed all three native CLI jobs and the Linux GUI build/scene for `6e98dc9`.
