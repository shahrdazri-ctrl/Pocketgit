# Engineering workflow

PocketGit is an owner-led, requirements-driven project maintained by
[shahrdazri-ctrl](https://github.com/shahrdazri-ctrl). Agent-assisted implementation
and analysis support a defined engineering process; acceptance rests on explicit
contracts, inspectable changes, and executable evidence.

## Requirement to implementation

A change starts with a concrete operation or reproduced failure. Establish which
[specification contract](../pocketgit.md), [format invariant](repository-format.md),
service boundary, or resource/failure limit it affects. For an extension, state the
observable before/after behavior and unsupported cases before implementing it.

The existing design records capture substantive decisions: typed SHA-256 identity,
canonical JSON, exclusive publication, cooperative lock ordering, complete checkout
planning, bounded LCS, and private disk preparation. Revisit an ADR when those
tradeoffs change rather than adding a parallel undocumented mechanism.

| Decision | Acceptance question |
| --- | --- |
| Storage or serialization | Are existing canonical payloads and IDs preserved? Is the new representation strict and independently readable? |
| Filesystem mutation | Which paths can change, when does publication begin, and what is recoverable after each failure? |
| Input handling | Are traversal, symlinks, nonregular files, aliases, malformed data, and Unicode handled at the boundary? |
| Algorithm choice | What bounds allocations and traversal? Is there an independent oracle or meaningful adversarial case? |
| Frontend behavior | Can services be tested without Picocli/JavaFX? Does presentation preserve exact stored data? |
| Distribution | Does the runnable artifact work in a fresh process and produce verifiable checksums? |

## Implement within the boundaries

Commands parse and render; services coordinate repository behavior; storage owns
encoding and safe publication. Reuse path validation, metadata access, object readers,
and lock ordering. Keep immutable values at interfaces and document retaining versus
streaming APIs. Inject a clock/reader/environment/failure hook where it makes a real
behavior testable; avoid abstractions that only mirror implementation details.

Every mutating operation must distinguish preparation, publication, and recovery.
Do not label separate atomic replacements as a durable transaction. Recheck observed
state where another writer/editor could invalidate a plan, and report partial
publication honestly. Preserve current user obstructions during failed recovery.

## Reproduce and review

For a defect, demonstrate failure before the fix when practical. Assertions should
inspect the result that matters: exact files, canonical bytes, index/HEAD/ref/log
contents, permissions, untouched paths, or bounded behavior. Independent seeded
oracles and process-level workflows test assumptions beyond the implementation.
Pure presentation/format changes reuse applicable existing checks rather than adding
low-value tests of their own structure.

Review the final diff for scope, compatibility, invariants, and diagnostics. Check
that current guides describe the result, historical reports remain historical, and
there are no new claims of unsupported behavior or unobserved platform results.
Code ownership and acceptance responsibility belong to the project owner; tool output
is evidence to assess rather than an authority that overrides the specification.

## Local checks

```bash
mvn spotless:apply
mvn -Dtest=CheckoutServiceTest test
mvn clean verify
sh examples/demo.sh
```

The pinned Spotless gate runs at `validate` over engine, test, and optional GUI Java
sources. `.editorconfig` defines UTF-8/LF and editor indentation. Full verification
runs compilation, unit tests, core coverage, packaged-process integration tests,
selected SpotBugs checks, and versioned release manifests.

When changing readers, dependencies, or the viewer:

```bash
mvn -Pgui clean verify
```

Save the preceding CLI artifact outside `target/` before a GUI `clean`. Exercise the
actual scene step in [CI](../.github/workflows/ci.yml) when UI or shared reader behavior
changes. Its offscreen tooling is separate from application runtime dependencies.

## Publication and provenance

The repository keeps a single `main` branch. Scheduled Dependabot version-update PRs
are disabled; dependency updates are reviewed against upstream advisories and the
same release gates. Jackson changes must preserve canonical object fixtures. GitHub
Actions remain pinned to official commit hashes rather than floating tags.

Record the actual source revision, toolchain, operating system, commands, and outcomes.
A coverage percentage is a regression gate rather than a substitute for assertions.
A configured CI matrix is not a passed run; a successful compile is not a displayed
JavaFX scene. [Validation](validation.md) links the current checks and retained
investigations without presenting old results as new evidence.

Commits should describe a concrete problem and resulting behavior. Project identity
is canonicalized through `.mailmap`, [CODEOWNERS](../.github/CODEOWNERS), and Maven
metadata. A published-history attribution rewrite is a separate review step because
it changes commit IDs and existing signatures while leaving source trees unchanged.
