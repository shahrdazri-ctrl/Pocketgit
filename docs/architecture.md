# PocketGit architecture

`PocketGit` assembles the Picocli command tree and handles errors at the CLI boundary. `RootCommand` displays help. `InitCommand` resolves the optional directory argument, invokes `RepositoryInitializer`, and formats its result. Placeholder commands do not perform I/O and return exit code 3.

`Repository` is an immutable path abstraction, normalizing its root to an absolute path and exposing metadata locations centrally. Its constructor performs no I/O. `RepositoryInitializer` resolves the target to its real filesystem path and owns initialization behavior. `RepositoryLocator` resolves an existing starting directory and walks parents to the nearest real `.pocketgit` directory, stopping at the filesystem root.

Services can be used directly without Picocli. CLI construction accepts an explicit working directory so tests never change process-global properties. Discovery currently identifies a metadata directory; full parsing and integrity verification belong to later phases. Invalid marker files or metadata symlinks produce errors rather than silently falling back to an outer repository.

The initializer uses an exclusive directory creation as its ownership gate and `CREATE_NEW` for metadata files. Only the invocation that creates `.pocketgit` writes initial contents. Reinitialization checks structural prerequisites and performs no writes. There is no Git/JGit dependency or external-process execution in application code. Integration tests launch Java only to test the packaged artifact.

Maven separates unit tests (`*Test`, Surefire) from packaged integration tests (`*IT`, Failsafe). Shade produces a runnable JAR containing Picocli and Jackson. Later phases will add domain objects and services as their behavior is implemented, rather than introducing empty classes for speculative functionality.

## Phase 2 additions

`ObjectStore` is the public object-database facade. `ObjectHasher` constructs canonical bytes and hashes them. `ObjectPaths` centralizes ID validation and rejects symlinked metadata, prefix directories, and object paths. `ObjectWriter` compresses and fully writes private temporary files, then exclusively publishes them through a hard link. `ObjectReader` validates one complete zlib stream, the canonical header, declared payload length, and SHA-256 before returning an immutable `StoredObject`.

`Blob` and `StoredObject` defensively copy byte arrays and implement content-based equality. Tree and commit object type envelopes are supported now; their semantic payload schemas arrive when those features are implemented.

`ObjectInspectionService` discovers repositories and provides verified reads and UTF-8 text inspection. `CatObjectCommand` only parses modes, invokes the service, and writes output. Raw payload output uses an injected `OutputStream` so binary bytes never pass through character encoding; help, metadata, and errors retain Picocli's text writers.

## Phase 3 additions

`Index` and `IndexEntry` are immutable, sorted, validated snapshots. `IndexStore` reads strict version-1 JSON, owns the update lock, and publishes snapshots by atomic replacement. `AddService` owns discovery, scope planning, tracked deletions, ignore matching, safe reads, Blob writes, file/directory transitions, and final index publication. `AddCommand` only parses its path, invokes the service, and prints the result.

`IgnoreMatcher` implements the documented small root ignore-file grammar without Git or external programs. `PathUtils` centralizes safe working-tree resolution and portable index paths. `HashUtils` supplies shared SHA-256 ID validation. `JsonUtils` configures fresh pretty printers with explicit LF line endings for deterministic metadata on all operating systems.

## Phase 4 additions

`Tree`, `TreeEntry`, `Commit`, and `AuthorIdentity` are immutable validated models. `ObjectCodec` defines canonical compact JSON payloads, explicit property order, strict semantic decoding, and formatted object inspection. `TreeBuilder` verifies indexed Blobs and constructs directory Trees bottom-up; `TreeReader` verifies and flattens stored snapshots without consulting working files. Traversal bounds limit deep graphs and repeated DAG expansion.

`CommitService` owns the full commit operation, with injectable clock/environment for deterministic tests. It holds index and commit locks, verifies the current parent snapshot, resolves identity, writes and verifies new objects, prepares ref/reflog replacements, rechecks HEAD/ref, and publishes metadata. CLI commands parse arguments and display service results. `ConfigService` provides discovery for repository-local configuration; `ConfigStore` owns its strict schema and atomic updates.

`HeadManager` reads attached symbolic HEAD, and `RefStore` reads/prepares existing branch refs. `MetadataFiles` centralizes bounded, no-follow reads and forced temporary-file writes with atomic replacement. `MetadataLock` owns exclusive marker files. `ReflogStore` prepares a complete replacement containing one additional ref-movement record. A branch ref and reflog are individually atomic; they are not one transaction. See [commits](commits.md) for exact failure and recovery limits.

## Phase 5 additions

`HeadSnapshotReader` resolves attached HEAD and flattens its verified Commit/Tree/Blob graph into an immutable index-shaped snapshot, retaining modes. An unborn branch yields an empty snapshot. `WorkingTree` fingerprints indexed files and discovers untracked/ignored paths across the entire repository. `ObjectHasher` now supports streaming canonical hashing without storing objects; `FileModeUtils` shares POSIX/fallback mode detection with `add`.

`StatusService` verifies indexed Blobs, compares HEAD/index and index/working snapshots independently, and returns immutable sorted `RepositoryStatus` categories. It rechecks the index and HEAD/ref after working-tree inspection to detect observed metadata movement. `StatusCommand` parses `--ignored`, calls the service, and flushes the formatted output; `StatusFormatter` renders deterministic plain text. Status creates no locks, objects, index replacements, or reflog entries. See [status](status.md) for the observation and concurrency limits.

## Phase 6 additions

`HeadManager.Head` models symbolic and detached references separately. `RefStore` provides validated reads, nested branch listing, verified updates, and exclusive new-ref preparation. `BranchService` uses `commit.lock` to serialize creation with commits and ref updates; it leaves working files, index, and HEAD unchanged. `MetadataFiles.Prepared.publishNew` publishes forced complete bytes through a non-overwriting hard link.

`ObjectStore.resolve` scans matching canonical object paths, rejects ambiguous prefixes and unsafe matching paths, and verifies selected bytes. `LogService` resolves HEAD and iteratively traverses parent Commits with active/visited sets and a traversal bound. Its injectable Commit reader makes cycle and deep graph behavior independently testable. `LogCommand`, `ShowCommand`, and `BranchCommand` only parse arguments, call services, and render results. `CommitFormatter` owns reusable text formatting. No engine operation invokes external Git.
