# Staging and add — Phase 3

The index is the proposed file snapshot for the next commit. `add` writes immutable Blob objects and updates index entries; it never changes working-tree files, HEAD, or branch refs. [Commits](commits.md) snapshot this index; [status](status.md) compares it with HEAD and working files.

```bash
pocketgit add file.txt
pocketgit add src/
pocketgit add .
pocketgit add -- -filename
```

Exactly one path argument is required. File and directory arguments resolve from the invocation's current directory. Absolute paths within the repository are also accepted. In accordance with the master prompt's whole-working-tree scope, **`add .` stages the entire repository even when invoked from a nested directory**. This differs from Git's current-directory scope. Use a named directory argument to narrow the scope.

New and modified regular files create/update entries. Unchanged files are verified and retain their existing Blob IDs; an unchanged index is not rewritten. Missing indexed files under the requested scope are removed from the index, staging their deletion. An absent directory can stage deletion of its indexed descendants. Missing paths that have no entries fail clearly. Entries outside the scope are preserved, except conflicting ancestors in file/directory replacements: staging `a/child` after indexed file `a` becomes a directory necessarily removes the old `a` entry.

The `.pocketgit` metadata name is reserved in every path component, case-insensitively, and is always excluded from scans. Index paths use `/` separators and reject absolute paths, dot segments, empty segments, backslashes, colons, and control characters. Spaces and Unicode are supported. Case-sensitive filename collisions and other platform-specific filename restrictions must be addressed before cross-platform checkout in later phases.

Requested paths are checked component by component before normalization. Symlinks canceled by `..` are still rejected, and paths may never step outside the repository. Recursive traversal does not follow symlinks. Unignored symlinks and special files fail the operation; explicitly requested symlinks always fail. Untracked files already excluded by an ignore rule are skipped without reading their contents. POSIX executable permission bits map to `EXECUTABLE_FILE`; files on platforms without POSIX attributes use `REGULAR_FILE`.

## Ignore rules

Only the root `.pocketgitignore` is read. It must be a regular UTF-8 file no larger than 1 MiB. Nested ignore files are not interpreted. Example:

```text
# Files that should not enter a PocketGit snapshot
target/
build/
*.class
*.log
.env
.idea/
.git/
```

Supported grammar:

| Form | Meaning |
| --- | --- |
| Blank line or leading `#` | Ignored |
| Literal without `/`, e.g. `.env` | Matches a basename at any depth |
| `*` | Zero or more characters within one path component |
| `?` | One character within one path component |
| Trailing `/` | Matches directories and their descendants, not a regular file of that name |
| Leading `/`, e.g. `/root.txt` | Anchors a pattern at the repository root |
| Slash inside a pattern, e.g. `src/generated/` | Root-relative pattern |

Matching is case-sensitive. Leading/trailing whitespace is stripped. Regex characters such as `[` are literal. Negation (`!`), recursive globstars (`**`), and backslash escapes are unsupported and produce a clear error; there is no claim of full Git wildmatch compatibility. Already indexed files are still updated or removed even if new ignore rules match them. `.pocketgitignore` itself is an ordinary file that may be staged.

## Publication and failures

The index update holds an exclusive `.pocketgit/index.lock` from loading the old snapshot through Blob writes and atomic index replacement. Concurrent writers fail with a retryable lock message rather than silently losing changes. Lock files are removed on ordinary success and exceptions. A crash can leave a lock behind; inspect active PocketGit processes before manually removing a stale lock. Automatic stale-lock recovery is deferred.

The next snapshot is published only after all relevant Blob writes succeed. Invalid paths, symlinks, malformed indexes, unsupported ignores, oversized files, or Blob-write failures leave the old index intact. Successful Blob writes from a later failed operation may remain as valid unreferenced objects, which is safe; garbage collection is future work.

Index snapshots are written to a private `.index-*.tmp` file, flushed, and moved with atomic replacement. Filesystems lacking atomic replacement fail instead of using a partial-write fallback. Ordinary failures clean up temporary files. Abandoned temporary files after process termination and directory durability across power loss remain future reliability work.

The file content bound is inherited from the object store: 64 MiB by default. Staging streams bytes into a private compressed object while hashing the canonical envelope, then publishes exclusively; existing objects are verified without retaining their content. File/directory conflicts are removed through sorted descendant ranges and actual ancestors, avoiding a full index scan for each candidate. The implementation detects size, modification time, file identity, and executable-mode changes during reads and asks for a retry; it does not guarantee a coherent multi-file snapshot against concurrent external edits or malicious directory renames. It stages the bytes captured during the successful operation.
