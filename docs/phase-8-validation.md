# Phase 8 validation

> Historical implementation record. Current behavior is documented in the [documentation index](README.md); current release checks are in [validation](validation.md).

Linux/Java 21: `mvn clean verify` passed 324 unit tests and 20 packaged integration tests, with zero failures/errors/skips. New checks cover text modifications, insertion/deletion, empty file creation, separated hunks, final newlines, CRLF, binary/invalid UTF-8, mode-only changes, matrix bounds, and 150 seeded random diff reconstructions. Restore checks cover source selection, nested directories, binary fidelity, corrupt objects, metadata preservation, traversal, and symlink/ancestor obstructions.

Real CLI requests displayed separate staged/unstaged unified diffs, restored from the index, and restored from a historical commit prefix. SHA-256 checks confirmed unchanged index, HEAD, and reflog. Status afterward correctly showed both staged and unstaged differences. The two packaged workflows repeat text and binary behavior in fresh Java processes.
