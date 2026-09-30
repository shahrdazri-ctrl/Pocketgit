# ADR-001: Typed SHA-256 immutable objects

Status: accepted.

## Context

Snapshots need stable identities across machines, support arbitrary binary content, and detect corrupt storage without relying on Git.

## Decision

Hash `type + " " + decimal byte length + NUL + payload` with SHA-256. Store one zlib stream per object under two-character ID fan-out directories. Blobs retain raw bytes; readers validate the stream, envelope, declared length, and hash. Publish a complete forced file through a non-overwriting hard link and verify an existing winner before reuse.

## Consequences

Type and byte length are part of identity. Equal canonical content deduplicates naturally, while compressor differences do not affect IDs. Existing corrupt objects are errors rather than silently rewritten. Full IDs are longer than SHA-1. Hard-link support is required, and the current implementation holds bounded payloads in memory.

## Alternatives

SHA-1 would be shorter and familiar, but has weaker collision resistance. Random IDs lose content deduplication and direct integrity checks. Hashing compressed bytes couples identity to compressor output. Ordinary replacement moves risk overwriting an object published concurrently.
