# PocketGit 1.0 repository format

This document describes the on-disk formats and validation rules needed for an independent reader. PocketGit metadata is not Git-compatible. All paths below are relative to a real, non-symlink `.pocketgit` directory at the working-tree root. Text is UTF-8; generated textual metadata uses LF, with a final LF except canonical object JSON.

## Layout and initial state

| Path | Contents/meaning |
| --- | --- |
| `HEAD` | Initially `ref: refs/heads/main\n`; current branch or detached Commit ID. |
| `refs/heads/main` | Initially exactly empty, representing an unborn branch; later a full Commit ID plus LF. |
| `index` | Strict JSON with `version: 1` and initially `entries: []`. |
| `config` | Initially `{"user":{}}`, pretty-printed with LF; no invented identity. |
| `objects/` | Immutable objects under two-character prefix directories. |
| `refs/heads/` | Regular branch-ref files; nested branch names form subdirectories. |
| `logs/HEAD` | Created by the first ref movement; commit and checkout reflog records. |

`init [DIRECTORY]` requires an existing directory and uses its physical path. It claims a new `.pocketgit` directory exclusively and never overwrites existing metadata. Reinitialization checks required directories and regular HEAD/index files without parsing every value or repairing the repository. Incomplete/unsafe metadata is retained and reported; initialization is not crash-transactional. Older repositories lacking `config` remain readable and can create it with `config user.name`/`user.email`.

## HEAD and branch refs

Symbolic HEAD is exactly `ref: refs/heads/NAME` with an optional final LF. Detached HEAD is exactly a 64-character lowercase SHA-256 Commit ID, also with optional LF. Readers understand detached HEAD; version 1.0's CLI does not expose detached checkout, and operations that require an attached branch reject it. HEAD reads are capped at 4 KiB.

A branch file contains either zero bytes (unborn) or one full lowercase hexadecimal Commit ID with optional final LF. Whitespace, extra lines, malformed IDs, symlinks, and nonregular files are errors. An empty missing branch is not equivalent to an unborn branch: a referenced branch must exist. New branch creation requires an existing commit and preserves HEAD/index/working files.

Branch names support nested `feature/login` paths. Validation rejects null/blank names, `HEAD`, leading `-` or `/`, trailing `/`, `..`, `@{`, controls/whitespace, and `~ ^ : ? * [ \\`. Every `/` component must be nonempty, must not begin/end with `.`, and must not end with `.lock`. Components also follow the portable-name rules below, including Windows device-name rejection. This prevents traversal and ambiguous metadata names.

## Object envelope, ID, and file path

Canonical uncompressed bytes are:

```text
ASCII(type) + ASCII(" ") + ASCII(decimalPayloadByteLength) + NUL + payload
```

Types are exactly lowercase `blob`, `tree`, or `commit`. Length is canonical unsigned decimal: `0`, or a nonzero digit followed by digits. Signs, leading zeros, whitespace, and extra fields are invalid. The header including NUL must fit in 128 bytes. The payload is arbitrary bytes and its declared byte length must exactly match.

The ID is lowercase hexadecimal `SHA-256(canonicalBytes)`, exactly 64 characters. ID `abcdef…` maps to `objects/ab/cdef…`; the filename has exactly 62 hexadecimal characters after a two-character prefix directory. IDs in stored metadata are always full IDs. CLI history/restore can resolve a nonempty unique lowercase hexadecimal prefix; `cat-object` requires a full ID.

An object file contains exactly one zlib stream holding the canonical bytes. Compression is Java's default `DeflaterOutputStream`; compressed bytes do not participate in identity and need not match across compressors. Reading must reject damaged/truncated streams, preset dictionaries, trailing bytes, concatenated streams, invalid headers, excess/missing payload bytes, and hash mismatches. Object files and their observed metadata paths must not be symlinks.

For the 11 ASCII bytes `hello world`, canonical bytes are `blob 11`, NUL, then `hello world`. The ID is:

```text
fee53a18d32820613c0527aa79be5cb30173c823a9b448fa4817767cc84c6f03
```

The default maximum payload is 67,108,864 bytes (64 MiB). The Java `ObjectStore` constructor can accept another bound; the CLI uses the default. Whole payloads are held in memory. A larger bound changes resource policy, not the format.

## Blob payload

Blob payloads are raw file bytes, including NUL, invalid UTF-8, or empty content. No newline or encoding transformation is applied by staging, checkout, or restore. Only higher-level text inspection/diff decides whether bytes can be displayed as text.

## Tree payload

Tree payloads are compact UTF-8 JSON without whitespace outside strings or a final LF. Property order is `entries`; each entry's order is `name`, `type`, `objectHash`, `mode`. For example, shown on multiple lines only for readability:

```json
{
  "entries": [
    {"name":"README.md","type":"BLOB","objectHash":"<64 lowercase hex>","mode":"REGULAR_FILE"},
    {"name":"src","type":"TREE","objectHash":"<64 lowercase hex>","mode":"DIRECTORY"}
  ]
}
```

Entries sort by Java `String.compareTo` (UTF-16 code-unit lexicographic order), independent of filesystem traversal and locale. Names are single valid path components; duplicates are forbidden. Blob entries have `REGULAR_FILE` or `EXECUTABLE_FILE`, Tree entries have `DIRECTORY`, and Commit entries are forbidden. The empty Tree payload is exactly `{"entries":[]}`.

Strings use Jackson 2.18.11 default JSON escaping with Unicode emitted as UTF-8. Semantic readers reject unknown/missing/null fields, duplicate keys, trailing JSON, invalid values, and payloads unequal to canonical reserialization, including reordered properties, whitespace, alternate escaping, or unsorted entries.

## Commit payload

Compact JSON uses this exact property order: `treeHash`, `parentHashes`, `authorName`, `authorEmail`, `timestamp`, `message`:

```json
{
  "treeHash":"<root Tree ID>",
  "parentHashes":[],
  "authorName":"Jane Developer",
  "authorEmail":"jane@example.com",
  "timestamp":"2026-01-02T03:04:05Z",
  "message":"Initial commit"
}
```

Stored payloads omit the formatting/newlines above. All IDs are full lowercase SHA-256. The initial commit has no parents; normal later commits have the previous branch tip as the sole parent. The model permits ordered multiple parents for future merge work; duplicates are forbidden. Timestamps use canonical `Instant.toString()` with UTC `Z`. Messages must be nonblank and contain no NUL; multiline Unicode is preserved.

Author name/email are nonblank, stripped of surrounding whitespace, and contain no control characters. Email additionally requires an interior `@` and no whitespace. Semantic decoding follows the same strict canonical rules as Trees and rejects noncanonical timestamp notation. A Commit must resolve to a Tree; each parent must resolve to a Commit.

Tree expansion is bounded to 256 path components and 100,000 expanded nodes, counting the root and repeated DAG expansion. Traversal detects active-path cycles while allowing shared subtrees. [Commits](commits.md) describes construction and publication in detail.

## Staging index

The index is pretty-printed UTF-8 JSON with explicit LF and a final LF. Its strict schema is:

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

Entries sort by Java lexicographic String order. Duplicate paths and file/directory ancestor conflicts are forbidden. IDs must refer to Blobs; file modes are `REGULAR_FILE` or `EXECUTABLE_FILE`. POSIX execute bits determine staging mode; platforms without POSIX permissions record regular files.

Paths are repository-relative with `/` separators. Empty/absolute paths, dot or empty components, backslashes, colons, ASCII control characters/DEL, and case-insensitive `.pocketgit` components are invalid. Internal spaces and Unicode are supported. Components must not end in a dot or space, contain `<>"|?*`, or use Windows device names (`CON`, `PRN`, `AUX`, `NUL`, `COM1`–`COM9`, `LPT1`–`LPT9`, including extensions and Windows-recognized superscript digits). This blocks aliases such as `.pocketgit./HEAD` on Windows.

Snapshots and Tree siblings reject distinct spellings with the same NFC-normalized, case-insensitive path key, including directory prefixes. For example, `README` plus `readme`, `src/A` plus `SRC/B`, and composed plus decomposed spellings of `café` are collisions. Each Unicode code point in the normalized key maps through `Character.toLowerCase(Character.toUpperCase(codePoint))`, independent of the process locale; this also catches Greek sigma/final-sigma aliases that ordinary lowercasing misses. Names are validated, never rewritten; valid object encodings and IDs are unchanged. This deliberately conservative portability policy also applies on case-sensitive filesystems. Older snapshots with these formerly accepted nonportable names are rejected rather than silently materialized. Platform path-length limits and additional filesystem-specific restrictions still apply.

The reader rejects unknown versions/fields, missing/null values, duplicate JSON keys, trailing values, scalar coercions, invalid paths/IDs/modes, and indexes above 16 MiB. The index describes the next commit, independently of both HEAD and working files.

## Configuration and ignore rules

`config` is strict JSON with a `user` object holding optional `name` and `email` string/null values. Unknown fields, duplicate keys, trailing data, coercions, and files above 1 MiB are rejected. Missing settings are not fabricated; config commands update one key while preserving the other. `POCKETGIT_AUTHOR_NAME`/`POCKETGIT_AUTHOR_EMAIL` override the respective setting. Blank overrides are errors. These values affect new Commit payloads, not repository-format identity.

The working-tree root's `.pocketgitignore` is a regular UTF-8 file no larger than 1 MiB. Blank lines and leading `#` comments are ignored; whitespace is stripped. Literal basename patterns and `*`/`?` match path components; leading `/` or embedded `/` anchors at the root; trailing `/` matches directories and descendants. Matching is case-sensitive. Negation, `**`, escapes, and nested ignore files are unsupported. Already indexed paths remain tracked. `.pocketgit` is always excluded; `.git` needs an explicit rule if desired. [Staging](staging.md) defines examples and scope.

## Reflog

`logs/HEAD` records one LF-terminated UTF-8 line per successful movement:

```text
<old ID or 64 zeroes> <new ID or 64 zeroes> <Instant timestamp> <operation>\n
```

Current CLI operations are `commit` and `checkout`. Zero IDs denote unborn endpoints. The log is bounded to 8 MiB. Appends prepare and replace the entire next log; invalid UTF-8 or a missing final LF fails before publication. Existing record semantics are not validated by `verify`. The log is observability, not automatic recovery; replay/reflog restoration is future work.

## Locks, publication, and recovery

| Lock | Operations |
| --- | --- |
| `index.lock` | Staging; paired with commit lock for commit, checkout, restore, and verify. |
| `commit.lock` | Commit/ref publication and branch creation; paired operations acquire index first. |
| `config.lock` | Local author-setting updates. |

Locks are regular exclusively created markers, non-waiting, and removed on ordinary completion. They do not contain credentials and must not be interpreted as an automatically expiring lease. Confirm no PocketGit process is active, inspect and back up state, then manually remove an abandoned lock. Never remove a live lock.

Objects are written to private `.tmp-*.object` files in their target prefix, compressed and forced, then published through a hard link without overwriting an existing object. A concurrent identical winner is read and verified. Metadata replacements use forced private files and atomic rename, with no non-atomic fallback; new refs use exclusive hard-link publication. Temporary names are implementation details, not valid object IDs.

Objects precede ref publication. The index, ref, HEAD, and reflog are individually atomic, not one durable transaction. Ordinary checkout/restore failures attempt rollback, but abrupt termination or power loss can leave partial files, stale locks, and temporary files. Directory fsync, durable transaction recovery, repair, and garbage collection are not provided. Hard links and atomic replacement are filesystem requirements; observed symlinks are rejected. Cooperative locks do not protect against external edits or malicious metadata-directory replacement during operations.

`verify` checks HEAD/refs, all stored objects (including unreachable ones), direct types/references, reachable graphs/snapshots, and index references. It reports illegal object filenames and corrupt data without repair. It does not validate configuration, ignore files, historic reflog semantics, or working-tree bytes. See [integrity and recovery](integrity.md), [checkout](checkout.md), and [diff/restore](diff-and-restore.md) for behavioral boundaries.
