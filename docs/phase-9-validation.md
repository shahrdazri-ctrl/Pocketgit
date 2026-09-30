# Phase 9 validation

Linux, Java 21/Maven 3.9.11: `mvn clean verify` passed **342 unit tests and 22 packaged integration tests**, with zero failures/errors/skips. JaCoCo reported **96.61% core line coverage (1,398/1,447 lines)**, above the enforced 80% minimum. CLI/bootstrap code is outside that core gate and covered by CLI and packaged workflows.

Failure injection covers missing Blobs/Trees/parents, truncated Commits, malformed index/HEAD/refs, object filename/hash mismatches, illegal storage names, unreachable corrupt objects, wrong-type index targets, locks, unwritable metadata, and symlinks. Checks compare before/after bytes to confirm verification does not repair files. Six seeded snapshot runs restored 25 mixed binary files each through checkout and restore.

The 1,000-file mixed text/binary benchmark measured: add 0.530 s, commit 0.184 s, status 0.123 s, verify 0.101 s. These are single-run observations on this cloud machine with instrumentation and a warm filesystem, not cross-platform performance guarantees.

The two new packaged workflows run normal staged/unstaged/history/branch/checkout/restore verification and deliberate index/object corruption in fresh processes. A separate terminal request verified a dirty working tree's intact metadata and objects, then verified a corrupted copy: the broken index was reported, left unchanged, and operation locks were cleaned up. Crash-atomic multi-file operations remain outside the current guarantee.
