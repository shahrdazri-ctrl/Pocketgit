# Repository format — Phase 1

All metadata lives in a real `.pocketgit` directory under the working-tree root. Metadata symlinks are rejected during initialization and reinitialization. Files created in this phase are UTF-8; textual line endings are LF on all platforms.

| Path inside `.pocketgit` | Initial state |
| --- | --- |
| `HEAD` | `ref: refs/heads/main` followed by LF |
| `index` | JSON object with integer `version: 1` and empty array `entries: []`, followed by LF |
| `refs/heads/main` | Empty file representing an unborn branch |
| `objects/` | Empty directory; object formats arrive in Phase 2 |
| `refs/heads/` | Directory for branch references |
| `logs/` | Empty directory; reflog writes arrive in a later phase |

`config` is exposed by the path abstraction but is not created yet. Author identity configuration belongs to the commit phase. Index entries, hashes, object storage, ignores, reflogs, and command locks are not implemented in Phase 1.

## Initialization policy

`init [DIRECTORY]` requires an existing directory; omitted arguments select the current working directory. Relative arguments resolve from the invocation's working directory. Physical paths are used for roots, including when the working directory was reached through a symlink.

Existing metadata is never reset, repaired, or deleted by `init`. A structurally complete existing repository is reported as already initialized. Reinitialization requires real `objects`, `refs`, `refs/heads`, and `logs` directories plus regular `HEAD` and `index` files; it does not parse their contents or require the original `main` branch to remain present. Full integrity checking is deferred to Phase 9.

If `.pocketgit` is a file, symlink, or incomplete directory, initialization fails with a clear error and preserves it. Inspect incomplete metadata before manually moving it aside and retrying; PocketGit does not make that decision for the user.

Initialization claims `.pocketgit` through atomic directory creation before writing new files exclusively. A second initializer never overwrites the first one's metadata; it may encounter incomplete structure while the first is writing and should be retried after the first finishes. Initialization is not a transactional or crash-durable operation yet: an interrupted write may leave partial metadata, and that metadata is deliberately retained. It is never advertised as successfully initialized by the failed invocation.

## Object database — Phase 2

An object's canonical uncompressed representation is the following byte sequence:

```text
ASCII(type) + ASCII(" ") + ASCII(decimal payload byte length) + NUL + payload
```

Types are exactly `blob`, `tree`, or `commit` in lowercase. Lengths use canonical nonnegative decimal notation: `0` or a nonzero digit followed by digits; no sign, leading zeros, whitespace, or extra fields. The NUL terminates the header. Payloads may contain any byte, including NUL. UTF-8 text is never assumed by the object layer. The stored header, including its delimiter, must fit within 128 bytes.

The ID is the lowercase hexadecimal SHA-256 of these canonical bytes, exactly 64 characters. Uppercase, abbreviated, and otherwise malformed IDs are rejected in Phase 2. An object with ID `abcdef...` lives at `objects/ab/cdef...`: the first two characters name a directory and the remaining 62 name its file.

The file contains exactly one zlib stream, produced by Java's default `DeflaterOutputStream`, holding the canonical bytes. Compression bytes do not participate in the ID and need not be identical across compressor implementations. Readers reject damaged or truncated streams, dictionaries, trailing bytes, concatenated streams, malformed headers, mismatched lengths, and hash mismatches. Metadata directories and object files must not be symlinks.

For example, a Blob containing the 11 ASCII bytes `hello world` has canonical bytes `blob 11`, NUL, then `hello world`, and ID:

```text
fee53a18d32820613c0527aa79be5cb30173c823a9b448fa4817767cc84c6f03
```

Blob payloads are raw file bytes. Tree and Commit payload schemas are deliberately not defined here yet; only their envelopes are implemented. The index and refs are untouched by object writes.

## Object publication and bounds

The writer creates a private `.tmp-*.object` file within the target prefix, writes compressed bytes, finishes the stream, and forces file contents before publication. `Files.createLink(destination, temporary)` publishes complete bytes without replacing any existing destination. A concurrent winner is read, verified, and compared before its ID is reused. Temporary files are removed after success or failure; any cleanup failure is reported. Existing corrupt objects are never silently rewritten.

Hard-link support is required for publication. There is no fallback to a weaker overwrite-prone move. This protects readers from partial objects and makes duplicate writes safe. It is not a repository-wide transaction or a guarantee of directory durability across power loss; directory fsync, cleanup of crash-orphaned temporary files, object garbage collection, and repository-wide locks are future work. The store assumes that metadata directories are not maliciously renamed by another process during an operation; it rejects symlinks observed at access time.

Default maximum payload: 64 MiB (67,108,864 bytes). `ObjectStore(repository, maxPayloadBytes)` can configure a different nonnegative bound; the CLI currently uses the default. Writes above the bound fail before creating objects. Reads bound decompressed canonical data before allocating the final payload, rejecting oversized streams and declared lengths. Whole objects are held in memory; streaming large-file support is future work. A larger configured bound changes resource policy, not the storage format or hash identity.

## Staging index — Phase 3

`index` remains UTF-8 JSON with explicit LF line endings and a final LF. Its schema is:

```json
{
  "version": 1,
  "entries": [
    {
      "path": "src/App.java",
      "blobHash": "fee53a18d32820613c0527aa79be5cb30173c823a9b448fa4817767cc84c6f03",
      "mode": "REGULAR_FILE"
    }
  ]
}
```

Entries sort by Java's lexicographic String order, independent of filesystem traversal. Duplicate paths and file/directory ancestor conflicts are rejected. Paths must be repository-relative and use `/`; they reject dot/empty components, metadata components, backslashes, colons, and control characters. IDs must be 64 lowercase hexadecimal characters. Modes are `REGULAR_FILE` or `EXECUTABLE_FILE`; staging uses POSIX execute permission bits where available and otherwise records regular files.

The reader rejects unknown versions/fields, missing or null required values, malformed JSON, duplicate JSON keys, trailing values, numeric coercions, invalid IDs/modes/paths, and indexes above 16 MiB. Phase 1's empty index remains compatible. Field validation is separate from the future whole-repository integrity walk: normal staging creates verified Blobs before publishing references to them.

`index.lock` is an exclusively created marker file held across load, Blob writes, and save. Readers can read an old or new complete index while another process updates it. The writer serializes a complete snapshot into a private `.index-*.tmp`, forces its contents, and atomically replaces `index`. It does not fall back to a non-atomic move. Locks and temporary files are removed on ordinary completion; process termination can leave them behind. See [staging](staging.md) for scope, ignore, symlink, and recovery policies. `add` does not update HEAD, branch refs, or reflogs.
