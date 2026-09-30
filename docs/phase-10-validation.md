# Phase 10 validation

Both release profiles passed locally on Linux with Java 21 and Maven 3.9.11. Configured cross-platform CI is not evidence of completed Windows/macOS runs.

## Observed CLI and engine checks

- **357 unit tests and 23 packaged integration tests** passed with zero failures/errors/skips in the completed CLI validation run.
- JaCoCo reported **96.77% core line coverage**, above the enforced 80% minimum. CLI/bootstrap/UI code is outside the core gate and is exercised through separate boundaries.
- The real `examples/demo.sh` workflow passed using the **PocketGit 1.0.0 CLI** in a fresh temporary directory (`/tmp/pocketgit-demo.qkSNjCvz`). It initialized metadata, configured a public demo author, staged/committed bytes, showed staged and unstaged diffs, created/switched branches, inspected history, and restored historical/index versions.
- Exact-byte assertions confirmed `hello.txt` after branch switching and both restore forms; checkout back to main removed the experiment-only tracked file. Final status was clean and `verify` reported **9 objects: 3 Commits, 3 Trees, and 3 Blobs**.

The phase adds regression tests for safety corrections found during review: preparation now enforces the cumulative content/backup budget even for deletion-only edits and before retaining oversized backups; ordinary-failure directory recovery preserves original POSIX permissions; a blocked directory recovery reports incomplete rollback while continuing independent file recovery and avoiding writes through a symlink obstruction. These remain ordinary-failure safeguards, not crash-atomic recovery.

## Documentation and real recording

The README, architecture, and complete repository-format specification were checked against the implemented engine. Six ADRs document object identity, deterministic snapshots, CLI/service separation, atomic publication/locks, checkout planning, and bounded LCS. Release and GUI guides distinguish CLI and platform-specific optional viewer artifacts.

`examples/demo.sh` passed shell syntax checking and its isolated workflow. `examples/record-demo.py` passed Python compilation; its PTY capture executed real commands and recorded **51 actual terminal events over 33.85 seconds** in asciicast version 2. The corresponding **1010 × 701 GIF has 137 frames and 35.50 seconds of playback**, including the final reading pause. Middle/final frames were inspected visually. The recording contains the actual hashes and results from that run; outputs were not fabricated. Demo directories are preserved for inspection and existing user directories are never reused.

Authored relative documentation links and `git diff --check` passed after this validation report was added. Python/Pillow are documentation-only tools and are not CLI runtime dependencies.

## Completed release gates

| Check | Current evidence |
| --- | --- |
| Final `mvn clean verify`, including SpotBugs | Passed end to end; 357 unit tests and 23 packaged integration tests, zero failures/errors/skips. |
| SpotBugs high-priority correctness/multithreaded findings | Zero selected findings and zero analysis errors for both profiles, with maximum effort and the focused include filter. |
| Versioned CLI/GUI JARs and GNU-compatible SHA-256 manifests | Both generated; `sha256sum -c` passed for each manifest, and each versioned JAR matched its unversioned counterpart byte for byte. Ant uses its `MD5SUM` output-format name with the SHA-256 algorithm. |
| Optional `mvn -Pgui clean verify` | Passed end to end; 357 unit tests and 23 packaged integration tests, zero failures/errors/skips; core coverage 1500/1550 lines (96.77%). |
| Actual JavaFX scene startup, metadata/changed-file assertions, and screenshot | Passed with JavaFX 21.0.9, software rendering, and checksum-verified TestFX Monocle 21.0.2. The three-commit/two-branch demo selected a commit, checked scene metadata/file rows, and captured the actual 1240 × 780 scene. All 16 repository/working files retained exact bytes and modes. |
| GUI CI scene commands | Extracted from the workflow and executed locally, including artifact checksum validation, a fresh two-commit/two-branch fixture, scene assertions, and identical before/after metadata manifests. |
| Ubuntu/Windows/macOS CI jobs | Configured; remote jobs not yet observed locally. |

The viewer bounds display to 2,000 commits and graph canvases to 15 explicit lanes plus one overflow column. Overflow commits retain their actual lane numbers and branch badges, and parent IDs remain available in details; ambiguous condensed edges are omitted. The [captured scene](screenshots/history-viewer.png) was inspected visually for the dark theme, readable graph, branch labels, and selected details. These offscreen checks do not establish native desktop availability on another machine. JavaFX emitted its classpath/unnamed-module warning; the smoke assertions passed and the process exited successfully.

Maven Central returned HTTP 429 during installation. The official Google Maven Central mirror restored dependency access with TLS/checksum verification intact. The corrected reusable cloud install script was executed successfully, including JDK/Maven archive checksums, tool activation, the complete CLI release build, and the 1.0.0 launcher. Writable JavaFX/font caches under `/workspace/.cache` resolved restricted-home cache warnings. Saved cloud configuration does not itself publish a new environment snapshot.

No native installer or external GitHub release publication is claimed. Multi-file operations retain the documented crash/concurrent-editor limits. See [release instructions](release.md), [GUI guide](gui.md), and [integrity/recovery](integrity.md).
