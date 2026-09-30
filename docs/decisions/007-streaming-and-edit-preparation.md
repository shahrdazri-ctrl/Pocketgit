# ADR-007: Stream Blobs and prepare working edits on private disk

Status: accepted.

## Context

The advertised 64 MiB Blob limit could exhaust a 96 MiB JVM heap because staging,
verification, extraction, and checkout retained several copies of each payload.
Checkout also deleted and recreated required parent directories, changing their
permissions. Per-file diff bounds did not bound an entire command's retained results.

## Decision

Use a shared streaming zlib validator for full canonical header, length, trailing
data, and SHA-256 checks. Staging hashes and compresses the declared bytes into a
private object preparation file, forces it, and publishes exclusively by hard link.
Existing objects are fully verified without retaining a payload. Typed semantic
reads reject mismatched types before retaining bytes; in-memory embedding APIs
remain available with defensive copies and unchanged object formats.

Prepare checkout/restore originals and replacements inside a private
`.pocketgit/edit-*` directory, retaining the 256 MiB combined content bound as a disk
budget. Preserve existing parent directories needed by replacement files. Own
preparation through `AutoCloseable`: close rolls back uncompleted edits, while
successful publication explicitly completes before cleanup. Incomplete working-file
rollback retains originals and a path/hash/permission manifest and reports its location.

Stream strict UTF-8 and binary classification across full Blob/working content,
retaining only a bounded text prefix. Keep existing per-input LCS bounds and add
32 MiB combined text and 250,000 hunk-line limits per command. Validate results
before streaming formatted output. Pretty object inspection has a separate 8 MiB
bound and escapes terminal controls; raw extraction preserves exact bytes.

## Consequences

A packaged maximum-size Blob workflow completes with a 96 MiB heap. Preparation
requires disk space and extra I/O; working paths are revalidated before mutation.
Streaming copy callers must keep partial output private until validation succeeds.
Tree/Commit decoding and embedding payload APIs still retain content, so this is
not a constant-memory guarantee for arbitrary repository metadata.

Ordinary rollback and manual recovery improve without making multi-file edits
crash-atomic. The manifest is not a durable transaction log and does not include
previous HEAD/index/reflog or complete directory metadata. Concurrent external
editors and interrupted preparation still require inspection.

## Alternatives

Raising the heap requirement would conceal multiple-copy growth and leave staging
quadratic. Keeping originals solely in memory prevents recovering them after an
obstructed rollback. Durable transaction recovery remains a separate future design.
