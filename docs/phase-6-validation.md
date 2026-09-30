# Phase 6 validation

Validated on Linux with OpenJDK 21.0.12.1 and Maven 3.9.11.

```bash
mvn clean verify
```

In the cloud environment, activate `/workspace/.tools/pocketgit-env.sh` and pass `-s /workspace/.tools/maven-settings.xml -B -ntp` to Maven for proxy access and the retained cache.

The full build compiled, packaged the executable JAR, and passed **288 unit tests and 17 integration tests**, with **0 failures, 0 errors, and 0 skips**. This includes 45 new unit test cases and three new packaged integration workflows. Windows/macOS execution has not been validated in this environment.

New unit tests cover unborn main, nested/sorted branches, branch capture without HEAD/index/file/reflog changes, duplicate and path-prefix conflicts, invalid names, existing locks, verified ref updates, detached HEAD reads, malformed HEAD, history order, unique/full object IDs, unknown and ambiguous prefixes, corrupt selected objects, missing/malformed/wrong-type parents, shared ancestors, cycle detection through an injected graph reader, 5,000-deep iterative traversal, metadata symlink rejection, and exclusive publication against a concurrent destination.

The packaged workflows run every command in a fresh Java process. They exercise init/config/add/commit/log/show/branch, nested discovery, UTF-8 paths and author names, multiline messages, short hash rendering, parent/Tree display, unborn and detached HEAD behavior, sorted current-branch markers, duplicate/invalid commands, help/usage validation, and a deleted parent object. History errors emit no partial output or stack traces. Read-only operations are compared against complete before/after file snapshots.

A separate terminal workflow created two commits, created `feature/login` at the first commit, and created `alpha` at the second. Full and compact logs displayed the second commit before the first, and `show` resolved the second commit's 12-character prefix. Manual metadata inspection confirmed the full ref IDs, symbolic HEAD, and commit-only reflog entries. SHA-256 checks before/after history inspection and branch creation confirmed unchanged tracked local edits, an untracked file, index, HEAD, and reflog. Status still reported the local edit and untracked file afterward.

Checkout remains an explicit exit-3 placeholder. No checkout, diff, restore, merge, or whole-repository verification behavior is claimed by Phase 6.
