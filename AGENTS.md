# Repository Development Instructions

Read [pocketgit.md](pocketgit.md) in full before implementing changes. It is the authoritative development specification for PocketGit.

- Follow its ten phases in order; assess the current implementation before selecting the next phase.
- Use Java 21+ and implement the version-control engine without delegating to Git or JGit.
- Keep CLI parsing and output separate from testable domain services.
- Preserve deterministic object formats, repository integrity, and user files; enforce the specification's checkout safety requirements.
- During implementation, run the applicable compilation, unit tests, integration tests, and real CLI workflows before declaring a phase complete.
- Update documentation when behavior or storage formats change. Commit completed phases independently.
- Report only behavior and validation supported by actual evidence; do not label unimplemented features as complete.
