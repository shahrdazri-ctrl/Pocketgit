# ADR-002: Deterministic JSON index and Trees

Status: accepted.

## Context

The staging area and nested snapshots should be inspectable, deterministic, portable, and straightforward to validate independently.

## Decision

Use strict version-1 JSON for the index with repository-relative `/` paths. Sort entries with Java's locale-independent `String.compareTo`. Build Trees bottom-up from verified index Blobs. Tree/Commit payloads use compact UTF-8 JSON, explicit property order, and canonical reserialization checks. File modes preserve regular/executable distinction; Trees use directory mode.

## Consequences

Filesystem walk order never affects a Tree ID. Unknown versions/fields, duplicate keys/paths, path escapes, and noncanonical objects fail clearly. JSON helps inspection at the cost of larger payloads than a custom binary representation. Portable-name validation rejects device names and case/Unicode aliases before publication. Filesystem path-length limits and additional platform restrictions still apply.

## Alternatives

A binary index could be smaller and faster but would require a separate inspection tool and more format machinery. Flat Trees lose directory structure. Native path separators or locale sorting would make snapshots machine-dependent. Permissive JSON decoding would permit several byte encodings for the same semantic object.
