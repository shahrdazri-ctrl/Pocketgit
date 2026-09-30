# ADR-005: Plan and validate checkout before editing

Status: accepted.

## Context

A branch switch can rewrite tracked files and remove obsolete paths. It must preserve user work, including untracked obstructions and file/directory transitions.

## Decision

Build a complete `CheckoutPlan` from verified current/index/target snapshots and working-tree observations. Block staged work and changes to affected tracked paths; protect conflicting untracked/ignored files, symlinks, and ancestor/descendant obstructions. Preserve unrelated local work. Recheck the plan, prepare bounded content/backups, then apply atomic file replacements and metadata publication with ordinary-failure rollback.

## Consequences

Known conflicts produce zero mutations and actionable paths. There is no force switch. Checkout can preserve unrelated unstaged changes, but intentionally blocks every staged change when switching branches. Backups consume bounded memory. Rollback improves ordinary-failure safety without promising recovery after process death or external concurrent edits.

## Alternatives

Blindly materializing the target snapshot could discard local or untracked files. Rejecting every dirty working tree would be safe but unnecessarily disruptive. Full durable transaction recovery is a future extension; detached checkout is deferred until branch switching is proven.
