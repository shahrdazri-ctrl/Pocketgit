# Phase 7 validation

> Historical implementation record. Current behavior is documented in the [documentation index](README.md); current release checks are in [validation](validation.md).

Linux, Java 21, Maven 3.9.11: `mvn clean verify` passed 303 unit tests and 18 packaged integration tests with zero failures/errors/skips. Checkout tests cover both directions, additions/deletions, staged and unstaged conflicts, ignored/untracked collisions, file/directory transitions, unrelated files, symlinks, permissions, corrupt targets, and rollback failures injected after file edits and each metadata publication step.

A real CLI workflow committed two versions, blocked checkout over private edits, verified byte hashes of the private file/index/HEAD/reflog, then switched both ways after restoring the clean version. Manual inspection confirmed symbolic HEAD and checkout reflog entries; status reported a clean tree after the switch. Cross-platform execution is deferred to CI; crash-atomic checkout and detached checkout are not claimed.
