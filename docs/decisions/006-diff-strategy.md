# ADR-006: Bounded longest-common-subsequence diffs

Status: accepted.

## Context

Users need readable staged and unstaged diffs, binary safety, and explicit resource limits. The engine cannot invoke Git or the system diff program.

## Decision

Implement line-based LCS in Java. Retain final-newline state, trim shared prefixes/suffixes, prefer removals before additions on ties, and render unified hunks with three context lines. Bound text inputs to 8 MiB and 100,000 lines per side before allocation, and reject changed-region matrices exceeding 4,000,000 cells. Store the matrix in one flat array so asymmetric inputs do not allocate millions of row objects. Stream binary/UTF-8 classification before text allocation. NUL or invalid UTF-8 content produces a binary-difference summary rather than raw bytes. Bound each command to 32 MiB combined text inputs and 250,000 retained hunk lines; validate all results before streaming formatted output.

## Consequences

The algorithm is small, deterministic, independently testable, and preserves newline-only differences. Prefix/suffix trimming makes small edits to otherwise equal files cheap. LCS remains quadratic for large changed regions; such comparisons fail clearly instead of exhausting memory. Mode changes can be reported independently of text changes.

## Alternatives

Myers offers better behavior for many large, similar inputs and is a future improvement. Unbounded LCS can exhaust memory. A third-party diff or external process would obscure the algorithm and add dependencies. Treating all input as text would expose binary garbage or lose invalid byte content.
