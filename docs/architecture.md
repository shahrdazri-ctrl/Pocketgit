# Phase 1 architecture

`PocketGit` assembles the Picocli command tree and handles errors at the CLI boundary. `RootCommand` displays help. `InitCommand` resolves the optional directory argument, invokes `RepositoryInitializer`, and formats its result. Placeholder commands do not perform I/O and return exit code 3.

`Repository` is an immutable path abstraction, normalizing its root to an absolute path and exposing metadata locations centrally. Its constructor performs no I/O. `RepositoryInitializer` resolves the target to its real filesystem path and owns initialization behavior. `RepositoryLocator` resolves an existing starting directory and walks parents to the nearest real `.pocketgit` directory, stopping at the filesystem root.

Services can be used directly without Picocli. CLI construction accepts an explicit working directory so tests never change process-global properties. Discovery currently identifies a metadata directory; full parsing and integrity verification belong to later phases. Invalid marker files or metadata symlinks produce errors rather than silently falling back to an outer repository.

The initializer uses an exclusive directory creation as its ownership gate and `CREATE_NEW` for metadata files. Only the invocation that creates `.pocketgit` writes initial contents. Reinitialization checks structural prerequisites and performs no writes. There is no Git/JGit dependency or external-process execution in application code. Integration tests launch Java only to test the packaged artifact.

Maven separates unit tests (`*Test`, Surefire) from packaged integration tests (`*IT`, Failsafe). Shade produces a runnable JAR containing Picocli and Jackson. Later phases will add domain objects and services as their behavior is implemented, rather than introducing empty classes for speculative functionality.
