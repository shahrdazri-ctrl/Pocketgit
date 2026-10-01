# Repository development instructions

Read [pocketgit.md](pocketgit.md) before changing engine behavior. It is the current
engineering specification; all ten original implementation milestones are complete.
Use the [documentation index](docs/README.md) for current contracts and the
[engineering workflow](docs/development-workflow.md) for change acceptance.

- Use Java 21+ and Maven 3.9+. Implement the engine without Git, JGit, or external diff/compression processes.
- Keep CLI parsing/presentation and the optional viewer separate from testable domain services.
- Preserve canonical object formats, repository integrity, portable names, and user files. Follow checkout's planning and recovery requirements.
- Run pinned formatting and checks appropriate to the change; full release verification is required for engine/dependency changes. Report only observed results.
- Add meaningful regressions for confirmed defects. Document behavior, storage compatibility, resource bounds, and failure limitations when they change.
- Keep commits focused and reviewable on `main`; do not create extra repository branches.
- Attribute project ownership to `shahrdazri-ctrl`. Published-history rewrites require a concrete preview and owner approval; do not infer permission from a request to prepare one.
