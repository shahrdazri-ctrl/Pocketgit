# ADR-004: Atomic metadata replacement and cooperative locks

Status: accepted.

## Context

Concurrent staging, commits, branch creation, and checkout must not publish partial metadata or lose cooperating updates. A multi-file durable database transaction is outside the initial release scope.

## Decision

Acquire exclusive non-waiting marker locks. Combined operations acquire `index.lock` then `commit.lock`; config updates use `config.lock`. Write complete private metadata files, force contents, and replace atomically. Publish new refs exclusively. Write and verify objects before pointing a ref to them. Report post-ref reflog failures as already-published commits.

## Consequences

Readers observe complete old or new metadata files, and cooperating writers fail clearly on lock contention. Unsupported atomic replacement/hard links fail instead of weakening safety. Ordinary completion removes locks; crashes can leave locks and temporary files. Individual replacements are not a crash-atomic repository transaction, and manual recovery requires inspection.

## Alternatives

Unprotected rewrites permit partial JSON and lost updates. A global lock is simpler but would unnecessarily couple author config with snapshots. Automatic timeout-based lock stealing risks interrupting a slow active writer. A journaled transaction system would improve crash recovery but requires substantially more recovery logic.
