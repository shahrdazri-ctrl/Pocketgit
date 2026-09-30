# Repository input safety audit

This follow-up audited `6e98dc9`, including the previous [resource and recovery work](resource-audit.md). It focused on adversarial repository inputs, Unicode correctness, portable branch metadata, and terminal presentation. It changes no object envelope, hashing rule, index schema, commit schema, or ref file format.

## Reproduced defects and fixes

| Finding | Reproduction before the fix | Result |
| --- | --- | --- |
| Exponential ignore matching | A rule consisting of `*a` repeated 18 times followed by `b`, against a 200-character `a` filename, still ran after three seconds and required terminating the process. Status and add share this matcher. | Direct greedy code-point matching has at most `pattern length × component length` comparisons per component and constant matching state. Rooted rules compare segments without copying every path prefix. |
| Supplementary Unicode ignore literals | A literal `😀.txt` rule did not match `😀.txt`. Regex quoting each UTF-16 code unit split the character. | Literals, `*`, and `?` operate on Unicode code points; `?` matches one emoji. Existing root/directory semantics remain covered. |
| Portable branch collisions | Linux accepted both `Feature` and `feature`, and both `Team/one` and `team/two`. `verify` incorrectly reported the resulting nonportable namespace as valid. | Creation checks all ref and directory spellings before mutation. Listing and verification reject manually introduced aliases. Case-mismatched reads, updates, and checkout fail on every platform. Native Unicode normalization can reuse an existing physical directory. |
| Unescaped path controls | `status` and `add` printed a filename containing the C1 control `U+009B` directly. Diff headers and restore confirmations used the same unsafe presentation pattern. | Every affected CLI label uses shared escaping, including Unicode bidi controls. Authors, branch labels, config values, init paths, and pretty object inspection receive the same protection. Stored names, object IDs, and restored bytes stay exact. |

## Regression coverage

- A time-bounded adversarial wildcard test and 2,000 seeded comparisons against an independent dynamic-programming glob oracle.
- Supplementary Unicode literal, wildcard, root-relative, and directory-propagation cases.
- Case, Greek sigma, sharp-S, NFC/NFD, nested-directory, and empty-directory branch collisions; failed operations preserve both metadata bytes and directories.
- Manually corrupted ref namespaces, case-mismatched ref reads/updates/checkout, and current-branch marking on normalizing filesystems.
- Every status category, directional controls in authors/messages, and single-line labels.
- Packaged CLI workflows for hostile ignore patterns, emoji ignores, branch aliases, exact filename storage, escaped diff/restore paths, and directional branch/config/author text.

## Validation

Local validation on 2026-09-30 used OpenJDK 21.0.12.1 and Maven 3.9.11 on Linux:

| Check | Observed result |
| --- | --- |
| `mvn clean verify` | 441 unit tests and 32 packaged-process integration tests; no failures, errors, or skips. |
| `mvn -Pgui clean verify` | The same 473 tests passed against the GUI artifact; no failures, errors, or skips. |
| JaCoCo core line coverage, both builds | 2,436 of 2,601 lines covered: 93.66%, above the 80% gate. |
| Selected SpotBugs checks, both builds | Zero findings or analysis errors. |
| Full 64 MiB file workflow with a 96 MiB heap | Passed in both packaged integration suites. |
| Executable `examples/demo.sh` | Snapshot, diff, branch, checkout, historical/index restore, clean status, and integrity verification passed. |
| Actual CI JavaFX scene script | Two branches, two commits, selected details, and 1,001 changed files displayed with bounded virtual rows. Metadata manifests were byte-identical before and after. |
| Versioned CLI and GUI release checksums | Both SHA-256 manifests matched their saved artifacts. |
| Documentation links and whitespace | No broken local Markdown links; `git diff --check` passed. |

The original hostile wildcard probe now returns correctly in approximately 0.04 seconds including JVM startup, compared with the earlier three-second timeout. This is one local observation, not a general throughput guarantee. The regression suite also checks an independent glob oracle and the existing checkout recovery, corruption, and snapshot round-trip cases.

Native CI results are recorded after the pushed revision completes the supported matrix.

The fixes bound per-rule wildcard matching; they do not promise constant time for arbitrarily many ignore rules. Full integrity verification still scales with the object database. Multi-file crash durability and protection against malicious concurrent directory renames remain the documented limits of the current version.
