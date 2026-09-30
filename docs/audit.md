# Deep engineering audit

Audited on 30 September 2026 against the complete ten-phase implementation. The cloud checkout was first fast-forwarded to GitHub's `4c199d5` so the audit covered the current engine, optional viewer, tests, packaging, and documentation.

The audit prioritized user-file preservation and repository integrity, followed by portability, bounded resource use, shared-graph performance, terminal output, and release repeatability. Fixes preserve valid canonical object encodings and avoid delegating engine operations to Git.

## Reproduced defects and corrections

| Finding | Correction and evidence |
| --- | --- |
| **Rollback could delete user files that the failed operation never touched.** It iterated every planned replacement, including paths created after preparation. | `WorkingTreeEditSafetyTest` reproduced deletion of a newly created `z` file, acceptance of changed backups, deletion of a formerly missing path, and repeated application. Recovery now tracks actual deletions/publications, checks prepared bytes/identity/modes before the first mutation, and leaves untouched files and their inode/timestamp unchanged. |
| **A blocked index recovery stopped HEAD and reflog recovery.** A failure after HEAD publication could leave HEAD on the target branch while files returned to the source. | A failure-injection regression replaces the index with a symlink after HEAD publication. Recovery attempts index, HEAD, log, and working files independently, preserves the external target, restores recoverable metadata, and reports incomplete recovery explicitly. |
| **Windows aliases could bypass metadata protection; colliding snapshot names could overwrite each other on another filesystem.** | Tests reject `.pocketgit./HEAD`, device names and extensions, trailing dots/spaces, case aliases, Unicode normalization aliases, directory-prefix aliases, and Greek sigma/sharp-S case variants. The same conservative portable policy applies to snapshots on every platform; refs share component validation. Staging a collision leaves the index and user files intact. |
| **Valid filesystem aliases were rejected by restore and absolute-path staging.** Native CI reproduced macOS `/var` versus `/private/var` and Windows `RUNNER~1` versus `runneradmin` failures. | Root aliases now resolve only up to the physical repository root. The original suffix remains subject to component-by-component validation, including interior symlinks canceled by `..`. A Linux symlink fixture first reproduced the failure, then verified exact restored bytes, unchanged index bytes, and continued rejection of interior symlinks. The viewer test now compares physical roots as discovery specifies. |
| **Jackson 2.18.2 predated published parser resource-limit fixes.** | Updated to 2.18.11, the latest patch in the same release line, which includes fixes for [document-length enforcement](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-2m67-wjpj-xhg9), [name-length enforcement](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-649p-m576-vr99), and [error-token growth](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-7hhh-6rmp-j9qf). These advisories cover multiple parser entry points; this audit does not claim each is exploitable through PocketGit's byte-array readers. Canonical serialization fixtures remain required. Weekly dependency-update PRs cover Maven and Actions. |
| **The diff matrix limit did not bound allocations made before the matrix.** Millions of newline tokens or a huge single line could consume memory first; asymmetric matrices allocated large numbers of tiny arrays. | Failing regressions demonstrated missing input/line limits. Text inputs now cap at 8 MiB and 100,000 lines per side before decoding/token allocation. LCS uses one flat array, retaining the 4,000,000-cell limit, deterministic edits, context, and newline semantics. |
| **Commit messages and text diffs emitted terminal control sequences directly.** | Unit and packaged-process tests exercise ANSI erase and OSC clipboard sequences. Human-readable output escapes control characters; stored metadata, domain edit scripts, and raw `cat-object` remain byte-exact. |
| **Windows native arguments and console encodings could replace Japanese author text with `???`; redirected help could contain ANSI coloring.** | The CLI entry point now uses explicit UTF-8 text writers and plain redirected help. A packaged regression checks stored author text, displayed Unicode, errors, and multiline messages with legacy stream encodings. Unicode arguments use the supported UTF-8 argument-file route, avoiding Java 21's Windows launcher code-page conversion; [the workflow is documented](release.md#unicode-arguments-on-windows-with-java-21). Integration comparisons normalize console CRLF while retaining exact checks of stored metadata and working-file bytes. CI's failure reporter explicitly emits UTF-8 so Unicode assertion failures remain visible. |
| **Verification/viewer history traversal revisited shared ancestors for every branch; snapshot expansion reread identical blobs.** | A forest fixture with 4,000 commits and 2,000 branch tips asserts exactly one reader call per distinct Commit. Iterative frames advance one parent at a time, retain cycle detection and parent order, and share visitation across roots. Repeated Blob IDs are checked once per snapshot scan, never cached across calls. |
| **Large changed-file lists created a scene node for every file; old selection requests accumulated work.** | The viewer uses virtualized changed-file rows and cancels/removes obsolete detail tasks. The actual CI scene fixture binds 1,001 changed files and asserts bounded cell creation, metadata/selection rendering, and identical before/after repository manifests. |
| **Repeated packaging changed shaded-JAR entry order.** | Stable output timestamps and pinned packaging plugins are paired with forced creation of a fresh unshaded input JAR. Two packaging runs with identical source/toolchain/configuration now produce identical CLI bytes. Maven Enforcer checks the documented Java/Maven minimums before compilation. |

Audited core operations were also reformatted for readability. The contributor guide explains the service boundaries, meaningful regression tests, format changes, and actual validation requirements. CI gains job time limits, cancellation of obsolete runs, and the large-snapshot viewer fixture.

## Validation

Both final release profiles passed locally with JDK 21.0.12.1 and Maven 3.9.11 on Linux. Existing [phase reports](phase-10-validation.md) retain their historical counts; they describe earlier runs, not this audit.

| Release gate | Observed result |
| --- | --- |
| CLI `mvn clean verify` | Passed: **406 unit tests + 27 packaged integration tests**, zero failures/errors/skips. |
| GUI `mvn -Pgui clean verify` | Passed: the same **406 + 27 tests**, zero failures/errors/skips. |
| Core line coverage | **1,962 / 2,055 lines (95.47%)** in both profiles; the 80% gate passed. |
| SpotBugs | Zero selected high-priority correctness/concurrency findings and zero analysis errors, both profiles. |
| Real terminal demo | Passed: branch switches, text diffs, historical/index restore, clean final status, and verification of 9 objects. |
| Actual GUI scene | Passed with 2 commits, 2 branches, and **1,001 changed files**; bounded cells, selected metadata, and identical before/after metadata manifests. |
| Repeated CLI packaging | Byte-for-byte identical JARs under the same source, dependencies, JDK, and configuration. |
| Release manifests | Both CLI and GUI versioned SHA-256 manifests passed checksum verification. |
| Native CLI CI | Passed on GitHub-hosted **Ubuntu, macOS, and Windows** in [run 36701843344](https://github.com/shahrdazri-ctrl/Pocketgit/actions/runs/36701843344), against `23f43ae`. |
| Native GUI CI | Passed on GitHub-hosted Ubuntu, including the actual 1,001-file headless scene and read-only manifests, in the same run. |

The final functional and repository checks included:
- Functional: the complete terminal demo, safe checkout conflicts, historical/index restore, integrity verification, and exact-byte checks.
- Viewer: the workflow's actual headless scene script, a 1,001-file commit, selected metadata, virtual rows, read-only repository manifests, and [screenshot inspection](screenshots/audit-history-viewer.png).
- Packaging: GNU-compatible checksum manifests and a byte-for-byte repeated CLI packaging comparison.
- Repository: documentation links, whitespace checks, no engine Git/JGit/process delegation, and review of the staged diff.

## Compatibility and remaining boundaries

The portable-name policy deliberately rejects formerly accepted nonportable names and colliding spellings; it does not rewrite or silently migrate existing snapshots. Valid names and canonical object IDs are unchanged. Platform path-length restrictions still apply; see [the format rules](repository-format.md).

Human-readable diff/log output escapes controls, including displayed carriage returns. This is an inspection format; raw object extraction remains the route for exact bytes. The diff's explicit limits can reject large text comparisons while staging, object storage, and byte-exact restoration retain their 64 MiB per-file limit.

The Windows Java 21 launcher still restricts direct arguments to the active Windows code page. PocketGit's UTF-8 argument-file route supports names and messages outside that page without requiring system locale changes. This is a documented native-launcher boundary, not a claim that already-lost argument characters can be reconstructed.

Ordinary-failure rollback is stronger, but multi-file checkout is still not crash-atomic. External editors do not participate in PocketGit locks, so revalidation cannot eliminate all races with edits after the final check. Power loss, process termination, or obstructed recovery can require manual inspection. These are documented design limits rather than claims of durable transaction recovery.

Local test counts and coverage above are Linux results. Native CLI release gates also passed on GitHub-hosted Ubuntu, macOS, and Windows; GUI release/scene validation ran on Linux. Native platform fixtures use capability checks where symlinks, POSIX permissions, or case-sensitive names are unavailable. No native installer, license change, GitHub release, or tagged publication was performed by this audit.

## Remote CI follow-up

After pushing the audited changes, [run 36695291192](https://github.com/shahrdazri-ctrl/Pocketgit/actions/runs/36695291192) reported failures in its macOS and Windows Maven jobs. The preceding phase-10 run also failed on these platforms. CI now publishes concise Maven/JUnit diagnostics as check annotations and uploads the full build log. Those annotations in [run 36696378931](https://github.com/shahrdazri-ctrl/Pocketgit/actions/runs/36696378931) identified the root-alias defect and physical/logical-root test mismatch described above.

Subsequent native packaged tests exposed console CRLF comparisons, legacy stream encoding, and Windows Java 21 argument conversion. The corrections retain exact repository-byte assertions, add stored-author and multiline-message checks through UTF-8 argument files, and document the supported input route. All four jobs in [the corrected run](https://github.com/shahrdazri-ctrl/Pocketgit/actions/runs/36701843344) completed successfully.

CI also reported deprecated Node 20 actions. Checkout/setup-java now use official v5 releases and upload-artifact uses v6, pinned to commit IDs resolved from their upstream tags. The final native run reports no deprecated-action warning.
