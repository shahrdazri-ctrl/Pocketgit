# Trees and commits — Phase 4

`commit -m MESSAGE` creates a snapshot of the index on the current attached branch. Working files are not read or rewritten, and the index is retained. A staged deletion removes the path from the new snapshot; file/directory transitions and executable modes are preserved. Untracked or unstaged changes are excluded. Discovery works from nested directories.

The first commit has no parents; subsequent commits have the previous branch tip as their sole parent. The model stores a parent list for future merge support. Commit messages must be nonblank and contain no NUL; Unicode and multiline messages are preserved. Timestamp is an `Instant` from the UTC system clock. The CLI displays a seven-character ID prefix, but storage/inspection uses full 64-character IDs.

An initially empty index or snapshot equal to HEAD prints `Nothing to commit.` and exits 0 without ref/log changes. `--allow-empty` creates such commits deliberately. Deleting all paths from a nonempty HEAD is a real change and creates an empty snapshot.

## Author configuration

```bash
pocketgit config user.name "Jane Developer"
pocketgit config user.email "jane@example.com"
pocketgit config user.name
```

Settings are repository-local UTF-8 JSON in `.pocketgit/config`:

```json
{
  "user": {
    "name": "Jane Developer",
    "email": "jane@example.com"
  }
}
```

New repositories start with an empty `user` object. Missing settings are represented by absent or null fields; repositories from earlier phases may lack the file. Setting either key preserves the other. Config rejects unknown keys/fields, duplicate JSON keys, trailing values, and non-string values (except null). Reads are capped at 1 MiB. Updates hold `config.lock`, force a prepared file, and replace atomically with LF formatting. Missing or corrupt settings are never silently overwritten with defaults.

Each non-null `POCKETGIT_AUTHOR_NAME`/`POCKETGIT_AUTHOR_EMAIL` overrides its corresponding local setting. Blank environment values are errors. Identity is stripped of surrounding whitespace and must be nonblank and contain no control characters. Email requires an interior `@` and no whitespace; this is a basic validation, not full RFC mailbox parsing. No identity is fabricated. Invalid/missing identity blocks a new commit and explains the config commands. Malformed config remains an error even with environment overrides.

## Canonical object payloads

Both types use the existing SHA-256 object envelope and single zlib stream. Payload bytes are compact UTF-8 JSON without indentation or a trailing newline. Property order is explicit. Strings use Jackson 2.22.3's default JSON escaping with Unicode emitted as UTF-8. IDs hash the type/byte-length/NUL header plus these exact payload bytes.

Tree property order is `entries`; entry order is `name`, `type`, `objectHash`, `mode`. Example shown formatted for readability:

```json
{
  "entries": [
    {"name": "README.md", "type": "BLOB", "objectHash": "<64 lowercase hex>", "mode": "REGULAR_FILE"},
    {"name": "src", "type": "TREE", "objectHash": "<64 lowercase hex>", "mode": "DIRECTORY"}
  ]
}
```

Names are single validated path components, sorted in Java lexicographic String order. Duplicates and metadata/dot/unsafe components are rejected. Blobs have `REGULAR_FILE` or `EXECUTABLE_FILE`; Trees have `DIRECTORY`; Commit entries are forbidden. Index entries still permit file modes only. The empty Tree payload is exactly `{"entries":[]}`. Trees are built bottom-up exclusively from verified indexed Blobs, so identical index snapshots produce identical root IDs.

Commit property order is `treeHash`, `parentHashes`, `authorName`, `authorEmail`, `timestamp`, `message`:

```json
{
  "treeHash": "<root tree ID>",
  "parentHashes": [],
  "authorName": "Jane Developer",
  "authorEmail": "jane@example.com",
  "timestamp": "2026-01-02T03:04:05Z",
  "message": "Initial commit"
}
```

Parent order is preserved, duplicates are forbidden, and IDs must be full lowercase SHA-256 strings. Timestamp uses canonical `Instant.toString()`. Semantic reads reject unknown/missing fields, invalid models, duplicate keys, trailing JSON, and payload bytes differing from canonical reserialization (including whitespace, property order, unsorted entries, or alternative escaping/timestamp notation). `cat-object --pretty` applies semantic validation to Trees/Commits; raw/type/size modes validate their envelope and hash without decoding their schema.

Snapshot construction/traversal permits at most 256 path components and 100,000 expanded nodes including the root, files, and directory entries. Reader traversal detects active-path cycles, permits shared subtrees, verifies all referenced Blobs, and counts repeated DAG expansion including empty directories. Normal object/index limits still apply: 64 MiB payload and 16 MiB index.

## Ref and reflog publication

HEAD must name an existing branch as `ref: refs/heads/NAME` with an optional final LF; detached HEAD is unsupported in this phase. Branch names reject traversal, reserved HEAD, dot components, `.lock` suffixes, backslashes, colons, controls/whitespace, and Git-style unsafe punctuation. Existing branch refs are either exactly empty (unborn) or a full Commit ID with an optional final LF. Missing refs, malformed IDs, symlinks, and nonregular metadata are errors; commit does not recreate missing refs. Branch creation and switching belong to later phases.

A commit holds `index.lock` then `commit.lock` throughout reading and publication. It verifies the old commit and snapshot before comparison, requires identity for a new commit, writes Trees and Commit, then verifies the new Commit's complete snapshot and immediate parent objects. It prepares both a ref replacement and the full next reflog before publishing either. HEAD and the old ref are rechecked immediately before publication. Ref publication occurs first, followed by the log. Prepared files are forced before atomic rename; no non-atomic fallback is allowed. Reads/writes reject symlinks observed in metadata paths.

Each LF-terminated reflog line has this form:

```text
<old ID or 64 zeroes> <new ID> <Instant timestamp> commit
```

The reflog is capped at 8 MiB. Invalid UTF-8 or a truncated final line blocks a commit before ref movement. Full validation of earlier log records and complete historical integrity checking belong to Phase 9.

Errors before ref publication preserve the old ref and log; newly written, unreachable objects may remain for later collection. Index and working files stay unchanged. Ref and log replacements are individually atomic, **not a combined transaction**. A crash or log-write failure after ref publication can leave a valid published commit without its reflog entry. A reported post-publication I/O failure includes the published commit ID and tells the user to inspect the branch/log before retrying. There is no automatic rollback of a valid published commit.

Hard-link object publication and atomic metadata replacement are filesystem requirements. File contents are forced, but directory fsync and recovery across power loss are not guaranteed. Cooperative locks prevent concurrent PocketGit add/commit updates; hostile directory renaming or external metadata edits during operations are outside this guarantee. Process termination can leave marker locks and temporary files; remove a stale lock only after confirming no operation is active and inspecting metadata. Preserve refs, index, objects, and user files. Durable transaction recovery, garbage collection, and automated repair remain future work.
