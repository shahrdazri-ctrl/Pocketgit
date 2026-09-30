# Release and distribution

CLI text is UTF-8 on every platform. Redirected help is plain text; interactive help can use terminal colors. Displayed lines can use native line endings, while stored repository formats and raw `cat-object` payload bytes remain exact.

The baseline release is a self-contained CLI JAR requiring Java 21+. Native installers and desktop packages are optional future additions. No release is automatically published by these instructions.

## CLI build

```bash
mvn clean verify
java -jar target/pocketgit.jar --version
java -jar target/pocketgit.jar --help
```

The build produces:

```text
target/pocketgit.jar
target/pocketgit-1.0.0.jar
target/pocketgit-1.0.0.jar.sha256
```

The two JAR paths contain the same runnable artifact: the unversioned path supports existing source launchers, while the versioned path is suitable for distribution. The checksum file is a GNU-format SHA-256 manifest containing the digest and artifact filename. On Linux, validate it from the artifact directory:

```bash
cd target
sha256sum -c pocketgit-1.0.0.jar.sha256
```

On macOS, use `shasum -a 256 -c pocketgit-1.0.0.jar.sha256`; in PowerShell, compare `Get-FileHash -Algorithm SHA256` with the manifest digest. Keep the manifest beside its corresponding versioned JAR.

Recipients can copy the versioned JAR anywhere and invoke `java -jar /absolute/path/pocketgit-1.0.0.jar COMMAND`. Repository discovery begins from the invocation directory. Source launchers in `bin/` refer to `target/pocketgit.jar`; preserve that relative layout when using them, or write a local wrapper to your installed JAR.

## Optional JavaFX viewer

The CLI build has no JavaFX runtime dependency. Build the optional desktop artifact on the platform where it will run:

```bash
mvn -Pgui clean verify
```

This produces `target/pocketgit-gui.jar`, `target/pocketgit-gui-1.0.0.jar`, and the versioned JAR's `.sha256` digest. From an initialized repository:

```bash
java -jar /absolute/path/pocketgit-gui-1.0.0.jar gui
```

The viewer requires a desktop display and platform-compatible JavaFX native libraries. It displays branches, commit history, selected metadata, and changed files; it is a read-only frontend over engine readers. It is not a merge or checkout UI. The JavaFX artifact is platform-specific; build a separate one on Windows, macOS, or Linux as needed. See the [GUI guide](gui.md) for interaction and local display prerequisites.

`clean` removes the preceding profile's artifacts. Build the CLI and GUI sequentially and preserve each release artifact outside `target/` if distributing both.

## Validation and quality gates

`mvn clean verify` runs unit tests, an 80% core line-coverage gate, packaging, packaged-process integration tests, and SpotBugs. SpotBugs uses maximum effort with high-priority correctness and multithreaded-correctness findings selected in `config/spotbugs-include.xml`; it does not enable a broad style rule set. CLI/bootstrap/UI code is excluded from the core coverage gate, and tested through their own boundaries.

CI builds on Java 21 for Ubuntu, Windows, and macOS. Local validation reports describe checks actually executed; configuring a matrix does not establish that all remote jobs have passed. Run release candidates through the supported matrix and inspect failures before publishing.

## Reproduce the terminal demonstration

```bash
sh examples/demo.sh
# Or use an artifact saved outside target/:
sh examples/demo.sh /absolute/path/pocketgit-1.0.0.jar
```

The script creates a fresh `mktemp` directory, uses a public demo author, executes real CLI commands, asserts branch/restore content, verifies the repository, and leaves the directory for inspection. It never reuses a user's directory. Command timestamps affect Commit IDs; each run has the same logical workflow rather than identical hashes.

The source recording is [screenshots/demo.cast](screenshots/demo.cast), asciicast version 2. Replay it with `asciinema play docs/screenshots/demo.cast` if asciinema is installed. The GIF renders those actual captured terminal events, with no invented command results. To capture/render a new recording on Linux/macOS, use Python 3 and Pillow:

```bash
python3 examples/record-demo.py --jar /absolute/path/pocketgit-1.0.0.jar
```

The recorder uses a PTY, real elapsed timestamps, and short pauses between commands for a roughly 20–40 second demonstration. It overwrites the source recording and GIF in `docs/screenshots/` by default; `--output-dir` can redirect these generated artifacts. The default font path is Linux DejaVu Sans Mono; use `--font /path/to/monospace.ttf` on systems with another font location. Python/Pillow are documentation tooling, not runtime dependencies.

## Publishing

Distribute the validated versioned JAR and its checksum together with links to the README, format specification, and applicable validation report. The project does not require remote hosting or Git compatibility to run. Tagging or uploading a release is a separate publication action; a committed packaging configuration is not evidence that an external release has been published.

## Reproducible packaging

The build pins the resource and JAR plugins, forces creation of a fresh unshaded input JAR before each shade operation, and sets `project.build.outputTimestamp` for stable ZIP entry timestamps. Java 21+ and Maven 3.9+ are checked at the start by Maven Enforcer. With the same JDK, platform, source tree, dependencies, and build properties, repeating `mvn package -DskipTests` should produce the same CLI JAR digest. `-DskipTests` is only for this second packaging comparison after a complete `mvn clean verify`; it does not replace the release gates. Override `-Dproject.build.outputTimestamp=...` deliberately when preparing a different release.
