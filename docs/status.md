# Status

```bash
pocketgit status
pocketgit status --ignored
```

Status inspects the whole repository from either its root or a nested directory. It returns success (exit 0) for both clean and dirty repositories. Execution failures return 1, invalid arguments return 2, and no partial status text is printed on failure. Output is plain text with deterministic ordering and no generated ANSI color codes.

## Three independent states

HEAD is the current committed snapshot, the index is the proposed next snapshot, and the working tree contains current files. File identity includes both canonical Blob SHA-256 ID and file mode.

| Category | Comparison |
| --- | --- |
| Staged new | Path exists in index, absent from HEAD |
| Staged modified | Path exists in both; Blob ID or mode differs |
| Staged deleted | Path exists in HEAD, absent from index |
| Unstaged modified | Indexed path is a working regular file with different Blob ID or mode |
| Unstaged deleted | Indexed path is absent or replaced by a directory/non-directory ancestor |
| Untracked | Working regular file absent from index and not ignored |
| Ignored | Untracked path matching root `.pocketgitignore`, including summarized directory subtrees |

These comparisons are independent: an edited/staged file edited again appears in both staged and unstaged modifications. A newly staged file deleted afterward is staged new and unstaged deleted. A staged deletion recreated on disk remains staged deleted and becomes untracked (or ignored under matching rules). Status does not infer renames.

Before the first commit, HEAD is treated as an empty snapshot and output includes `No commits yet`. A clean result has no staged/unstaged/untracked changes; ignored content alone does not make it dirty. Empty untracked directories are omitted because snapshots track files.

Example:

```text
On branch main

Changes to be committed:
  new file:   src/App.java

Changes not staged:
  modified:   src/App.java

Untracked files:
  scratch.txt
```

Paths sort lexicographically within each section. The domain `RepositoryStatus` also exposes sorted immutable lists for each category. Ignored paths are collected in the domain result, hidden by default in text, and displayed with `--ignored`.

## Working-tree inspection

Each indexed regular file is read every time using a streaming SHA-256 digest of `blob <length>`, NUL, and exact file bytes. Status does not rely on cached timestamps or file sizes to decide equality. Same-length edits with restored timestamps, binary bytes, and empty files are compared accurately. POSIX execute bits use the same mode policy as `add`; other filesystems fall back to regular mode.

Streaming hashing avoids loading a working file into memory or writing a Blob. Indexed and committed Blobs are also verified using bounded buffers; status does not retain their payloads. Even a tracked file enlarged beyond the 64 MiB staging/object limit can be reported as modified; staging it still obeys the existing limit. Untracked and ignored content is classified by path without reading its bytes or computing its hash.

Tracked files are checked explicitly before directory discovery, so new ignore rules never suppress an indexed modification or deletion. An ignored directory without indexed descendants is summarized as `path/` and its contents are not traversed. Where an ignored directory contains indexed descendants, status descends to report ignored siblings/subdirectories while retaining tracked paths. The root ignore grammar is shared with `add` and documented in [staging](staging.md).

Metadata components named `.pocketgit`, case-insensitively, are always excluded at any depth and do not appear as ignored/untracked. Nonignored symlinks and special files fail clearly; symlinks are never intentionally followed. Ignored symlinks may be listed without reading their targets, and ignored subtrees are skipped. Missing indexed paths and paths below a directory replaced by a regular file count as unstaged deletions; permission and other I/O errors are not silently treated as deletions. Unsupported repository-relative path names produce an error rather than being silently omitted.

## Integrity, read-only behavior, and concurrent changes

Status verifies the current Commit, Trees, their Blobs, and each unique indexed Blob. Missing, corrupt, or mistyped objects and malformed HEAD/ref/index/ignore data are errors instead of a false clean result. The HEAD flattening API is `HeadSnapshotReader.read(Repository)`, returning branch, optional commit ID, and an immutable `Index` of files/modes. Existing branch refs must be present even when unborn. Status requires HEAD to be attached to a branch; history and object inspection can read a detached HEAD.

Status does not read author configuration or reflog contents, and it is not a full repository integrity audit. Corrupt configuration/reflog data does not prevent unrelated status inspection; use `pocketgit verify` to inspect unreachable objects, other branches, and older parent history.

The command leaves file contents, objects, index, refs, HEAD, configuration, and reflog unchanged. It creates no lock or temporary files and can read complete atomic metadata snapshots while another operation holds a marker lock. Filesystem access-time bookkeeping may occur during reads.

Working fingerprints compare size, modification time, file identity, type, and mode around the read and reject observed in-flight changes; streamed byte counts must match the declared length. After inspection, status reloads the index and rechecks HEAD/ref, rejecting observed movement with a retry error. It does not lock the working tree or provide a simultaneous filesystem transaction: edits after a path was inspected, metadata ABA changes, or malicious renaming can escape these checks. Checkout revalidates its plan immediately before later writes.
