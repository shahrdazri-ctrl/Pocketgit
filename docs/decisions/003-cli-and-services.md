# ADR-003: Keep CLI parsing separate from the engine

Status: accepted.

## Context

Filesystem behavior, object validation, and checkout safety must be testable without launching a terminal process or changing the process working directory.

## Decision

Picocli commands parse arguments, call domain services, and format immutable results. Services accept explicit working paths and use repository/storage abstractions. Clocks, environment lookups, and selected readers/writers are injectable where failure or deterministic behavior needs testing. The optional viewer reuses engine readers rather than shelling out to commands.

## Consequences

Unit tests inspect domain results and exact disk bytes. Packaged integration tests separately validate parsing, errors, and runnable-JAR behavior. The CLI remains small, and another frontend can reuse the same integrity checks. More boundaries exist than in one monolithic command implementation.

## Alternatives

Embedding all behavior in Picocli commands would simplify initial wiring but entangle test setup, output, and filesystem mutation. An external Git backend would avoid implementing the engine but contradict the project's purpose and independent format.
