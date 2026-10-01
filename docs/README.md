# Documentation

PocketGit's current contracts, design rationale, and verification records are organized
below. The project is designed and maintained by
[shahrdazri-ctrl](https://github.com/shahrdazri-ctrl).

## Start here

| Goal | Read |
| --- | --- |
| Build and use the application | [Project README](../README.md), [release guide](release.md) |
| Understand the engine boundary | [Engineering specification](../pocketgit.md), [architecture](architecture.md) |
| Implement an independent reader | [Repository format](repository-format.md), [object API](object-database.md) |
| Assess safety and failure behavior | [Checkout](checkout.md), [integrity/recovery](integrity.md) |
| Assess engineering evidence | [Validation](validation.md), [development workflow](development-workflow.md) |

## Current behavior

- [Staging and ignore rules](staging.md): scope, path validation, streaming publication, index atomicity.
- [Trees and commits](commits.md): canonical payloads, identity, snapshot construction, ref/reflog publication.
- [Status](status.md): committed/staged/working state, actual byte hashing, ignored and untracked paths.
- [History and branches](history-and-branches.md): graph traversal, prefix resolution, portable refs, detached read behavior.
- [Checkout](checkout.md): conflict planning, working-file preparation, recovery, and crash boundaries.
- [Diff and restore](diff-and-restore.md): text/binary classification, bounded LCS, selected-file replacement.
- [Integrity and recovery](integrity.md): whole-store checks, lock coordination, retained originals, manual inspection.
- [JavaFX viewer](gui.md): read-only history, display bounds, worker cancellation, scene validation.
- [Release and distribution](release.md): artifacts, checksums, Unicode arguments, reproducible packaging.

## Design decisions

| Record | Decision |
| --- | --- |
| [ADR-001](decisions/001-object-format.md) | Typed SHA-256 immutable objects and exclusive publication. |
| [ADR-002](decisions/002-index-and-tree-format.md) | Strict deterministic JSON for index and snapshots. |
| [ADR-003](decisions/003-cli-and-services.md) | CLI parsing/presentation separate from engine services. |
| [ADR-004](decisions/004-metadata-publication.md) | Forced atomic metadata replacement and cooperative locks. |
| [ADR-005](decisions/005-safe-checkout.md) | Complete checkout planning and conflict detection before mutation. |
| [ADR-006](decisions/006-diff-strategy.md) | Bounded flat-array LCS with deterministic edits and newline handling. |
| [ADR-007](decisions/007-streaming-and-edit-preparation.md) | Streaming Blobs and private disk preparation for recoverable edits. |

## Engineering investigations

The following reports preserve reproduced defects, regression evidence, measured
limits, and the exact revisions/environments used. Their historical test counts
must not be presented as current release results.

- [Safety and portability audit](audit.md): recovery, path aliases, terminal controls,
  shared graphs, parser updates, viewer virtualization, and native CI investigations.
- [Resource and recovery audit](resource-audit.md): maximum-size low-heap Blobs,
  directory permissions, staging complexity, cumulative diff bounds, private backups.
- [Repository input audit](input-safety-audit.md): adversarial ignores, supplementary
  Unicode, portable branch namespaces, and safe presentation.

## Historical milestone records

The original implementation sequence is complete. Its acceptance reports remain as
an engineering history rather than a list of outstanding features.

[1: foundation](phase-1-validation.md) · [2: objects](phase-2-validation.md) ·
[3: staging](phase-3-validation.md) · [4: commits](phase-4-validation.md) ·
[5: status](phase-5-validation.md) · [6: history](phase-6-validation.md) ·
[7: checkout](phase-7-validation.md) · [8: diff/restore](phase-8-validation.md) ·
[9: reliability](phase-9-validation.md) · [10: distribution/viewer](phase-10-validation.md)
