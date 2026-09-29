# PocketGit — Deep Implementation Master Prompt

## Mission

Build **PocketGit**, a polished, portfolio-grade version-control system written from scratch in **Java 21+**.

PocketGit should reproduce the most important conceptual mechanics of Git without shelling out to Git, embedding Git, or using JGit for the implementation.

The finished project should demonstrate strong practical knowledge of:

- Java architecture
- object-oriented design
- immutable domain models
- filesystem operations
- hashing
- content-addressable storage
- serialization
- command-line application design
- graph-like commit history
- branches and HEAD references
- diff algorithms
- restoration of historical state
- automated testing
- error handling
- repository integrity
- developer tooling
- packaging
- documentation

The project should remain understandable enough that another engineer can inspect the repository, understand the storage format, run the test suite, and extend it.

The target is **not** to clone every Git feature. The target is to build a smaller version-control engine exceptionally well.

---

# Core Product Definition

The command-line application should expose a command named:

```bash
pocketgit
```

Core commands:

```bash
pocketgit init
pocketgit status
pocketgit add <path>
pocketgit add .
pocketgit commit -m "message"
pocketgit log
pocketgit diff
pocketgit branch
pocketgit branch <name>
pocketgit checkout <branch>
pocketgit restore <file>
pocketgit restore --commit <hash> <file>
```

Recommended optional commands:

```bash
pocketgit show <commit>
pocketgit cat-object <hash>
pocketgit ls-tree <hash>
pocketgit rev-parse HEAD
pocketgit verify
```

PocketGit must never delegate its core functionality to the real Git executable.

Forbidden implementation shortcuts:

```text
git init
git add
git commit
git status
git diff
git checkout
JGit-backed repository operations
external Git process execution
```

The implementation must be genuinely owned by the Java application.

---

# Technology Constraints

Use:

- Java 21 or newer
- Maven or Gradle
- Picocli for CLI parsing
- Jackson for metadata serialization where appropriate
- JUnit 5
- AssertJ if desired
- java.nio.file for filesystem operations
- java.security.MessageDigest for SHA-256
- java.time for timestamps

Optional:

- JavaFX for a commit graph visualizer
- Testcontainers only if a future feature genuinely needs it

Avoid unnecessary dependencies.

The repository should work on:

- Windows
- macOS
- Linux

All filesystem code must account for platform-independent path handling.

---

# High-Level Repository Layout

Recommended structure:

```text
pocketgit/
├── pom.xml
│   or build.gradle
├── README.md
├── LICENSE
├── .gitignore
├── docs/
│   ├── architecture.md
│   ├── repository-format.md
│   ├── decisions/
│   │   ├── 001-object-format.md
│   │   ├── 002-index-format.md
│   │   └── 003-diff-strategy.md
│   └── screenshots/
├── src/
│   ├── main/
│   │   └── java/
│   │       └── com/pocketgit/
│   │           ├── PocketGit.java
│   │           ├── cli/
│   │           ├── repository/
│   │           ├── storage/
│   │           ├── model/
│   │           ├── diff/
│   │           ├── refs/
│   │           ├── services/
│   │           ├── validation/
│   │           └── util/
│   └── test/
│       └── java/
│           └── com/pocketgit/
│               ├── unit/
│               ├── integration/
│               └── fixtures/
└── examples/
```

Suggested internal class structure:

```text
com.pocketgit
├── PocketGit.java
├── cli
│   ├── RootCommand.java
│   ├── InitCommand.java
│   ├── AddCommand.java
│   ├── CommitCommand.java
│   ├── StatusCommand.java
│   ├── LogCommand.java
│   ├── DiffCommand.java
│   ├── BranchCommand.java
│   ├── CheckoutCommand.java
│   ├── RestoreCommand.java
│   ├── ShowCommand.java
│   └── VerifyCommand.java
├── model
│   ├── Blob.java
│   ├── Tree.java
│   ├── TreeEntry.java
│   ├── Commit.java
│   ├── IndexEntry.java
│   ├── RepositoryStatus.java
│   └── ObjectType.java
├── repository
│   ├── Repository.java
│   ├── RepositoryLocator.java
│   ├── RepositoryInitializer.java
│   └── WorkingTree.java
├── storage
│   ├── ObjectStore.java
│   ├── ObjectHasher.java
│   ├── ObjectReader.java
│   ├── ObjectWriter.java
│   └── IndexStore.java
├── refs
│   ├── HeadManager.java
│   ├── RefStore.java
│   └── BranchService.java
├── services
│   ├── AddService.java
│   ├── CommitService.java
│   ├── StatusService.java
│   ├── CheckoutService.java
│   ├── RestoreService.java
│   └── LogService.java
├── diff
│   ├── DiffEngine.java
│   ├── DiffHunk.java
│   ├── DiffLine.java
│   └── DiffFormatter.java
├── validation
│   ├── RepositoryValidator.java
│   ├── RefNameValidator.java
│   └── IntegrityChecker.java
└── util
    ├── FileUtils.java
    ├── HashUtils.java
    ├── JsonUtils.java
    └── PathUtils.java
```

---

# Repository Storage Format

Inside every initialized PocketGit project:

```text
.pocketgit/
├── HEAD
├── index
├── config
├── objects/
├── refs/
│   └── heads/
│       └── main
└── logs/
```

Recommended meaning:

```text
HEAD
```

Contains:

```text
ref: refs/heads/main
```

A branch ref contains a commit ID:

```text
d91c2a71f69d...
```

The object store should use SHA-256.

Example:

```text
SHA-256:
a72fc8e91dd31c...
```

Stored as:

```text
.pocketgit/objects/a7/2fc8e91dd31c...
```

Objects should be immutable.

Never overwrite an existing object with different content.

A stored object should include enough information to distinguish its type.

Conceptually:

```text
blob <size>\0<contents>
tree <size>\0<serialized tree>
commit <size>\0<serialized commit>
```

Exact storage format may differ, but it must be:

- deterministic
- documented
- testable
- unambiguous
- resistant to accidental collisions between object types

---

# Global Engineering Rules

Throughout every phase:

1. Keep business logic out of Picocli command classes.
2. CLI classes should parse arguments, invoke services, and print results.
3. Core services must be testable without launching the CLI.
4. Never silently ignore filesystem failures.
5. Never delete user files unless the command explicitly requires it.
6. Never overwrite uncommitted work during checkout without safety checks.
7. Normalize internal paths consistently.
8. Preserve deterministic object hashing.
9. Avoid static global state.
10. Prefer small cohesive classes.
11. Prefer immutable records/value objects when appropriate.
12. Add tests while implementing features, not afterward.
13. Every phase must leave the project compiling.
14. Every phase must leave all existing tests passing.
15. Do not move to the next phase while known core defects remain.

---

# PHASE 1 — Project Foundation, CLI Skeleton, and Repository Initialization

## Objective

Create the full Java project foundation and make:

```bash
pocketgit init
```

fully functional.

At the end of this phase, a user can initialize a repository and PocketGit can reliably detect that repository from nested directories.

---

## 1.1 Build Setup

Create a Java 21 project.

Configure:

```text
Java source = 21
UTF-8
JUnit 5
Picocli
Jackson
```

Set up:

- compilation
- unit tests
- integration tests
- runnable application
- executable JAR

Recommended Maven plugins:

- maven-compiler-plugin
- maven-surefire-plugin
- maven-shade-plugin or equivalent

The final packaged JAR should support:

```bash
java -jar pocketgit.jar --help
```

---

## 1.2 Root CLI

Create:

```java
@Command(
    name = "pocketgit",
    mixinStandardHelpOptions = true,
    version = "PocketGit 0.1.0"
)
```

Root help should be clean and professional.

Expected:

```text
Usage: pocketgit [COMMAND]

A lightweight version-control system written in Java.

Commands:
  init
  status
  add
  commit
  log
  diff
  branch
  checkout
  restore
```

Commands not implemented yet may initially exist as placeholders, but they must clearly report:

```text
Not implemented yet.
```

Do not fake successful behavior.

---

## 1.3 Repository Locator

Implement repository discovery.

Given:

```text
/project/.pocketgit
/project/src/main/java
```

If the current working directory is:

```text
/project/src/main/java
```

PocketGit should walk upward until it finds:

```text
/project/.pocketgit
```

Create:

```java
RepositoryLocator
```

API example:

```java
Optional<Path> findRepositoryRoot(Path startingDirectory)
```

Requirements:

- resolve absolute path
- normalize path
- walk parent chain
- terminate at filesystem root
- never loop indefinitely
- support Windows drive roots

---

## 1.4 Init Command

Implement:

```bash
pocketgit init
```

It must create:

```text
.pocketgit/
    HEAD
    index
    objects/
    refs/
        heads/
            main
    logs/
```

Recommended initial values:

`.pocketgit/HEAD`

```text
ref: refs/heads/main
```

`.pocketgit/refs/heads/main`

Empty file until first commit.

`.pocketgit/index`

Either:

```json
{
  "version": 1,
  "entries": []
}
```

or another explicitly documented format.

---

## 1.5 Init Safety

If repository already exists:

```bash
pocketgit init
```

should not destroy it.

Return:

```text
PocketGit repository already exists at /path/project
```

Never reset HEAD, refs, index, or objects.

---

## 1.6 Repository Abstraction

Create a central:

```java
Repository
```

It should expose locations such as:

```java
Path root();
Path metadataDirectory();
Path objectsDirectory();
Path refsDirectory();
Path headsDirectory();
Path headFile();
Path indexFile();
```

Avoid scattering string literals like `.pocketgit/objects` throughout the project.

---

## 1.7 Tests

Create tests for:

- init creates required structure
- init creates HEAD correctly
- init is idempotent
- locator finds repository from root
- locator finds repository from deeply nested directory
- locator returns empty outside repository
- paths work with directories containing spaces
- Unicode paths are supported

Use temporary directories.

Example:

```java
@TempDir
Path tempDir;
```

---

## Phase 1 Acceptance Criteria

The following must work:

```bash
mkdir demo
cd demo
pocketgit init
```

Result:

```text
Initialized empty PocketGit repository in .../demo/.pocketgit
```

Running:

```bash
pocketgit init
```

again must preserve the repository.

The full test suite must pass.

---

# PHASE 2 — Content-Addressable Object Database

## Objective

Implement PocketGit's immutable object storage.

This is the technical core of the project.

---

## 2.1 Object Types

Support:

```text
BLOB
TREE
COMMIT
```

Create:

```java
enum ObjectType {
    BLOB,
    TREE,
    COMMIT
}
```

Each object should have a deterministic canonical representation.

---

## 2.2 SHA-256 Object IDs

Implement:

```java
ObjectHasher
```

Given identical object type and identical bytes, it must always generate the same ID.

Recommended canonical hashing payload:

```text
<type> <byte-length>\0<payload>
```

Example:

```text
blob 11\0hello world
```

Hash using:

```java
MessageDigest.getInstance("SHA-256")
```

Represent the digest as lowercase hexadecimal.

Expected length:

```text
64 characters
```

---

## 2.3 Object Path Mapping

Given:

```text
a72fc8e91dd31c...
```

store under:

```text
objects/a7/2fc8e91dd31c...
```

Implement:

```java
Path pathForHash(String hash)
```

Validate:

- correct length
- hexadecimal only
- lowercase normalization policy
- reject malformed IDs

---

## 2.4 Blob Objects

A Blob represents raw file bytes.

Do not assume text.

Blob storage must support:

- Java source
- images
- PDFs
- ZIP files
- binary files
- empty files

Never decode a Blob as UTF-8 unless a higher-level feature explicitly needs textual diffing.

Suggested model:

```java
public record Blob(byte[] content) {}
```

Be careful with array mutability.

Prefer defensive copies.

---

## 2.5 Object Writer

Create:

```java
ObjectWriter
```

Responsibilities:

1. canonicalize object
2. calculate hash
3. create prefix directory
4. write object atomically
5. avoid rewriting an existing identical object
6. return object ID

Use atomic or temporary-file-based writes where practical.

Pseudo-flow:

```text
calculate bytes
↓
calculate SHA-256
↓
determine destination
↓
if exists:
    verify or trust immutable object
else:
    write temporary file
    move into destination
```

---

## 2.6 Object Reader

Create:

```java
ObjectReader
```

It should:

- locate hash
- read object bytes
- parse object header
- determine object type
- validate declared length
- reject corrupt object
- return typed result

Errors should differentiate:

```text
object not found
invalid object header
payload length mismatch
unknown object type
```

---

## 2.7 Compression

Optional but recommended:

Compress stored objects using:

```java
DeflaterOutputStream
InflaterInputStream
```

If compression is implemented, document it.

Hash the canonical uncompressed representation, not the compressed bytes.

This ensures storage method does not change object identity.

---

## 2.8 Debug Command

Implement:

```bash
pocketgit cat-object <hash>
```

Useful behavior:

```bash
pocketgit cat-object --type <hash>
pocketgit cat-object --size <hash>
pocketgit cat-object --pretty <hash>
```

This significantly improves debuggability.

---

## 2.9 Tests

Test:

- same bytes generate same hash
- different bytes generate different hashes
- object type affects hash
- empty Blob
- binary Blob
- 1 MB Blob
- write then read preserves bytes
- corrupt header rejected
- incorrect payload length rejected
- malformed hash rejected
- object path mapping is correct
- object writer does not create duplicates

---

## Phase 2 Acceptance Criteria

The application must be capable of storing arbitrary file contents in the object database and retrieving those bytes exactly.

The object system must be independently testable before implementing staging.

---

# PHASE 3 — Staging Area and `add`

## Objective

Implement the index/staging area.

Commands:

```bash
pocketgit add file.txt
pocketgit add src/
pocketgit add .
```

---

## 3.1 Index Model

The index represents the proposed state of the next commit.

Each entry should include at minimum:

```text
repository-relative path
blob object ID
file mode/type
```

Potential model:

```java
public record IndexEntry(
    String path,
    String blobHash,
    FileMode mode
) {}
```

Index:

```java
public record Index(
    int version,
    List<IndexEntry> entries
) {}
```

Sort entries deterministically by repository-relative path.

---

## 3.2 Index Storage

Implement:

```java
IndexStore
```

Responsibilities:

```java
Index load()
void save(Index index)
```

Use atomic replacement:

```text
write index.tmp
flush
move index.tmp -> index
```

Do not leave half-written index files after common failures.

---

## 3.3 Path Handling

All index paths must be repository-relative.

Good:

```text
src/main/java/App.java
README.md
```

Bad:

```text
C:\Users\person\project\README.md
/home/person/project/README.md
```

Normalize separator policy.

Internally recommend:

```text
/
```

even on Windows.

Create explicit conversion helpers.

---

## 3.4 Add Single File

For:

```bash
pocketgit add README.md
```

Workflow:

1. resolve repository
2. resolve requested path
3. ensure it is inside working tree
4. read file bytes
5. create Blob object
6. store Blob
7. create/update index entry
8. save index atomically

Output:

```text
Staged README.md
```

---

## 3.5 Add Directory

For:

```bash
pocketgit add src
```

walk recursively.

Ignore:

```text
.pocketgit/
```

Always.

Do not accidentally stage PocketGit's internal metadata.

---

## 3.6 Add Dot

Implement:

```bash
pocketgit add .
```

Walk entire working tree.

Stage:

- new files
- modified files

Handle deleted files by removing their corresponding index entries when the path is included in the add scope.

This behavior should be documented.

---

## 3.7 Ignore File

Implement:

```text
.pocketgitignore
```

At minimum support:

```text
target/
build/
*.class
*.log
.env
.idea/
```

You do not need full Git wildmatch compatibility initially.

But clearly define supported syntax.

Recommended initial rules:

- blank lines ignored
- `#` starts comment
- literal file/directory
- `*`
- `?`
- trailing `/` indicates directory

Document known limitations.

---

## 3.8 Symlinks

Choose and document a policy.

Recommended MVP:

```text
Do not follow symbolic links recursively.
```

Either:

- store symlink target as a special entry, or
- reject symlinks with a clear message

Do not accidentally traverse outside the repository.

---

## 3.9 Security

Prevent:

```text
../../outside.txt
```

from staging files outside repository root.

Any path passed to `add` must resolve underneath repository root.

Reject escapes.

---

## 3.10 Tests

Cover:

- add new file
- add modified file
- add empty file
- add binary file
- add directory
- add dot
- deleted file removed from index
- ignored file excluded
- `.pocketgit` always excluded
- paths outside repo rejected
- duplicate add updates existing entry
- deterministic index order
- Unicode filename
- filename containing spaces

---

## Phase 3 Acceptance Criteria

After:

```bash
echo hello > hello.txt
pocketgit add hello.txt
```

the index must point to a valid Blob containing exactly the contents of `hello.txt`.

---

# PHASE 4 — Trees, Commits, and Commit Creation

## Objective

Implement real repository snapshots and:

```bash
pocketgit commit -m "message"
```

---

## 4.1 Tree Objects

A Tree maps names to:

```text
Blob
or
nested Tree
```

Example working directory:

```text
README.md
src/
    Main.java
```

should produce conceptually:

```text
root tree
├── README.md -> blob abc
└── src -> tree def
              └── Main.java -> blob ghi
```

Create:

```java
Tree
TreeEntry
```

Tree serialization must be deterministic.

Sort entries.

Never rely on filesystem traversal order.

---

## 4.2 Build Tree From Index

Implement:

```java
TreeBuilder
```

Input:

```text
Index entries
```

Output:

```text
root tree object ID
```

It should create nested Trees recursively.

Example index:

```text
README.md
src/Main.java
src/util/FileUtil.java
```

constructs:

```text
root
├── README.md
└── src
    ├── Main.java
    └── util
        └── FileUtil.java
```

---

## 4.3 Commit Model

Commit should contain:

```text
tree ID
parent commit ID, optional
author
timestamp
message
```

Recommended:

```java
public record Commit(
    String treeHash,
    String parentHash,
    String authorName,
    String authorEmail,
    Instant timestamp,
    String message
) {}
```

For future merge support, consider storing:

```java
List<String> parentHashes
```

even if Phase 4 creates only one parent.

---

## 4.4 Configuration

Create `.pocketgit/config`.

Example:

```json
{
  "user": {
    "name": "Jane Developer",
    "email": "jane@example.com"
  }
}
```

Allow:

```bash
pocketgit config user.name "Jane Developer"
pocketgit config user.email "jane@example.com"
```

Optional for this phase, but strongly recommended.

Alternative:

Environment variables:

```text
POCKETGIT_AUTHOR_NAME
POCKETGIT_AUTHOR_EMAIL
```

Never silently fabricate identity.

If unavailable:

```text
Author identity not configured.
Run:
  pocketgit config user.name "Your Name"
  pocketgit config user.email "you@example.com"
```

---

## 4.5 Commit Creation

Workflow:

```text
load index
↓
build trees
↓
write Tree objects
↓
find current HEAD branch
↓
read parent commit if one exists
↓
create Commit
↓
store Commit
↓
update current branch ref atomically
↓
append reflog entry
```

---

## 4.6 Empty Commits

Default behavior:

If index represents the same tree as HEAD:

```text
Nothing to commit.
```

Do not create a duplicate commit.

Optional:

```bash
pocketgit commit --allow-empty -m "..."
```

---

## 4.7 Reflog

Create:

```text
.pocketgit/logs/HEAD
```

Each ref movement can append:

```text
<old> <new> <timestamp> <operation>
```

This is excellent portfolio-level engineering because it makes ref changes observable.

---

## 4.8 Crash Safety

Ref update must happen only after all objects are successfully written.

Never point branch ref to a missing Commit object.

Recommended ordering:

```text
write blobs
write trees
write commit
fsync where practical
update ref
```

---

## 4.9 Tests

Test:

- first commit has no parent
- second commit points to first
- commit tree corresponds to index
- nested directories create nested trees
- same index generates same tree ID
- commit message stored correctly
- commit author stored correctly
- branch ref updated
- empty commit rejected
- objects exist before ref update
- commit history survives application restart

---

## Phase 4 Acceptance Criteria

This should work:

```bash
pocketgit init
echo hello > hello.txt
pocketgit add .
pocketgit commit -m "Initial commit"
```

A valid commit must exist in the object store and `refs/heads/main` must point to it.

---

# PHASE 5 — Status Engine and Working Tree Comparison

## Objective

Implement an accurate:

```bash
pocketgit status
```

This phase should make PocketGit feel like a real version-control tool.

---

## 5.1 Three States

PocketGit must distinguish:

```text
HEAD
Index
Working Tree
```

Comparisons:

```text
HEAD -> Index
```

shows staged changes.

```text
Index -> Working Tree
```

shows unstaged changes.

---

## 5.2 Status Categories

Support:

```text
staged new files
staged modified files
staged deleted files

unstaged modified files
unstaged deleted files

untracked files
ignored files
```

Suggested domain object:

```java
RepositoryStatus
```

with categorized lists.

---

## 5.3 HEAD Tree Reader

Implement an API to flatten the current committed Tree into:

```text
path -> blob ID
```

Example:

```java
Map<String, String> readHeadSnapshot()
```

---

## 5.4 Index Snapshot

Represent:

```text
path -> blob ID
```

from the current index.

---

## 5.5 Working Tree Snapshot

Walk current repository.

For each relevant file:

- compute Blob hash
- compare with index
- classify

Optimization is optional initially.

Correctness first.

Later optimization can use:

- file size
- last modified time
- cached metadata

But never allow stale metadata to produce incorrect status.

---

## 5.6 Output

Example:

```text
On branch main

Changes to be committed:
  new file:   src/App.java
  modified:   README.md

Changes not staged:
  modified:   src/App.java
  deleted:    notes.txt

Untracked files:
  scratch.txt
```

Color is optional.

If added, detect terminal capability.

Do not produce unreadable ANSI output when redirected to file.

---

## 5.7 Clean State

Expected:

```text
On branch main
nothing to commit, working tree clean
```

---

## 5.8 No Commits Yet

Before first commit:

```text
On branch main
No commits yet
```

Index and working tree status must still work.

---

## 5.9 Tests

Create explicit scenarios for every status category.

Test combinations such as:

```text
tracked file modified then staged
tracked file modified after staging
tracked file deleted
tracked file deleted and staged
new staged file
new untracked file
ignored untracked file
```

Make assertions against domain-level `RepositoryStatus`, not only printed text.

---

## Phase 5 Acceptance Criteria

Status classification must be trustworthy.

No user should lose work because the status engine misidentifies a changed file.

---

# PHASE 6 — Commit History, References, and Branching

## Objective

Implement history traversal and branches.

Commands:

```bash
pocketgit log
pocketgit branch
pocketgit branch feature
```

---

## 6.1 HEAD Manager

Implement:

```java
HeadManager
```

It should understand:

```text
symbolic HEAD
detached HEAD
```

Primary representation:

```text
ref: refs/heads/main
```

Future detached representation:

```text
<commit-hash>
```

Even if detached HEAD is not initially exposed, design for it cleanly.

---

## 6.2 Ref Store

Implement:

```java
RefStore
```

Operations:

```java
Optional<String> readRef(String ref)
void updateRef(String ref, String newHash)
List<String> listBranches()
```

Validate ref names.

Reject names with:

```text
..
leading /
trailing /
empty path segments
control characters
```

Prevent traversal attacks such as:

```text
../../objects
```

---

## 6.3 Branch Creation

Command:

```bash
pocketgit branch feature-login
```

Creates:

```text
refs/heads/feature-login
```

pointing to the same commit as current HEAD.

If no commits exist, define behavior clearly.

Recommended:

Allow unborn branches only if architecture handles them cleanly; otherwise report:

```text
Cannot create branch before first commit.
```

---

## 6.4 Branch Listing

```bash
pocketgit branch
```

Expected:

```text
* main
  feature-login
```

Sort alphabetically.

---

## 6.5 Log Traversal

Implement:

```java
LogService
```

Starting at HEAD:

```text
Commit C
↓ parent
Commit B
↓ parent
Commit A
```

Render:

```text
commit d91c2a71...
Author: Jane Developer <jane@example.com>
Date:   2026-09-29T15:32:01Z

    Add repository status engine
```

Optional compact mode:

```bash
pocketgit log --oneline
```

Output:

```text
d91c2a7 Add repository status engine
5b8ce10 Add staging area
021caba Initial commit
```

---

## 6.6 Hash Prefix Resolution

Allow:

```bash
pocketgit show d91c2a7
```

rather than requiring all 64 characters.

Implement prefix matching against object store.

Rules:

- no matches -> unknown object
- exactly one -> resolve
- multiple -> ambiguous object ID

---

## 6.7 Show Command

Implement:

```bash
pocketgit show <commit>
```

Display:

- commit metadata
- parent
- tree
- message
- optionally diff against parent

---

## 6.8 Corruption Protection

History traversal should detect:

- missing parent
- malformed commit
- cycles

Cycles should theoretically be impossible under immutable hashes, but defensive detection is valuable.

---

## 6.9 Tests

Test:

- initial branch is main
- branch points to current commit
- duplicate branch rejected
- invalid name rejected
- list marks current branch
- log returns correct order
- short hashes resolve
- ambiguous prefixes rejected
- corrupt parent handled gracefully

---

## Phase 6 Acceptance Criteria

Users must be able to create branches and inspect repository history without touching working-tree content yet.

---

# PHASE 7 — Checkout and Safe Branch Switching

## Objective

Implement:

```bash
pocketgit checkout feature-login
```

This phase must prioritize **data safety**.

---

## 7.1 Checkout Responsibilities

Switching branches requires:

1. resolve target branch
2. resolve target commit
3. resolve target tree
4. compare current working tree for unsafe changes
5. update files
6. delete tracked files absent from target
7. update index to target snapshot
8. update HEAD
9. write reflog

---

## 7.2 Never Overwrite User Work Silently

If switching would overwrite an unstaged local modification:

Abort.

Example:

```text
error: local changes would be overwritten by checkout:
    src/App.java
Commit or stage your changes before switching branches.
```

Also protect relevant untracked files.

Example:

If target branch contains:

```text
config.json
```

but current working tree has an untracked `config.json`, do not overwrite it.

---

## 7.3 Checkout Planner

Before mutating disk, calculate a plan.

Create:

```java
CheckoutPlan
```

Potential contents:

```text
filesToWrite
filesToDelete
conflicts
targetIndex
```

If conflicts are non-empty:

```text
perform zero mutations
```

This avoids partial checkouts.

---

## 7.4 Applying Checkout

Recommended mutation algorithm:

```text
validate complete plan
↓
materialize target files into temporary area where feasible
↓
write/update files
↓
remove obsolete tracked files
↓
remove empty directories if appropriate
↓
write new index
↓
update HEAD
```

Do not delete unrelated untracked directories.

---

## 7.5 File Permissions

At minimum preserve executable/non-executable distinction on POSIX when possible.

Model:

```text
REGULAR
EXECUTABLE
```

On Windows, gracefully degrade.

---

## 7.6 Checkout Same Branch

If:

```bash
pocketgit checkout main
```

while on main:

```text
Already on 'main'
```

No destructive rewrite required.

---

## 7.7 Detached Checkout

Optional:

```bash
pocketgit checkout <commit>
```

If implemented:

```text
HEAD = commit hash
```

Warn:

```text
You are in detached HEAD state.
```

Do not implement unless branch checkout is already robust.

---

## 7.8 Tests

Critical tests:

- switch between branches
- modified file changes correctly
- newly introduced file appears
- removed tracked file disappears
- untracked unrelated file remains
- local modification conflict aborts
- untracked overwrite conflict aborts
- index matches target branch afterward
- HEAD changes only after safe checkout
- failed checkout leaves repository unchanged

---

## Phase 7 Acceptance Criteria

Branch checkout must be reliable enough that deliberately constructed conflict scenarios never destroy local user work.

---

# PHASE 8 — Diff Engine and Restore

## Objective

Implement line-oriented textual diffs and safe restoration.

Commands:

```bash
pocketgit diff
pocketgit diff --staged
pocketgit restore <file>
pocketgit restore --commit <hash> <file>
```

---

## 8.1 Diff Engine

Implement your own line-based diff algorithm or a clearly explained algorithmic implementation.

Recommended options:

- Longest Common Subsequence
- Myers diff

For portfolio value, Myers is excellent.

However, a correct LCS implementation is acceptable for an MVP.

Do not call the system's `diff` program.

---

## 8.2 Domain Model

Create:

```java
DiffResult
DiffHunk
DiffLine
```

Line types:

```text
CONTEXT
ADDED
REMOVED
```

---

## 8.3 Unified Diff Formatting

Example:

```diff
--- a/src/App.java
+++ b/src/App.java
@@ -10,4 +10,5 @@
 public void run() {
-    System.out.println("Hello");
+    System.out.println("Hello PocketGit");
+    start();
 }
```

Implement hunk context.

Recommended default:

```text
3 lines
```

---

## 8.4 `pocketgit diff`

Compare:

```text
Index
vs
Working Tree
```

Only display unstaged changes.

---

## 8.5 `pocketgit diff --staged`

Compare:

```text
HEAD
vs
Index
```

Show changes that will enter the next commit.

---

## 8.6 Binary Files

Do not dump binary bytes.

Detect likely binary content.

Reasonable heuristic:

- NUL byte detection
- UTF-8 decode failure
- configurable threshold

Output:

```text
Binary files differ: assets/logo.png
```

---

## 8.7 Restore From Index

```bash
pocketgit restore src/App.java
```

Should replace the working-tree copy with the version staged in the index.

Require confirmation only if product design chooses it; Git does not, but clear documentation is essential because restore is destructive to unstaged changes.

---

## 8.8 Restore From Commit

```bash
pocketgit restore --commit d91c2a7 src/App.java
```

Workflow:

```text
resolve commit
↓
walk commit tree
↓
find path
↓
read Blob
↓
write working-tree file
```

Define whether index is changed.

Recommended:

```text
restore --commit modifies working tree only
```

unless `--staged` is explicitly provided.

---

## 8.9 Directory Restoration

Optional but valuable:

```bash
pocketgit restore src/
```

Restore all tracked files underneath path.

Must never restore outside repository.

---

## 8.10 Tests

Diff tests:

- single line modification
- insertion
- deletion
- file creation
- file deletion
- multiple separated hunks
- empty file
- trailing newline difference
- binary file

Restore tests:

- index restore
- commit restore
- missing path
- path traversal rejection
- directory creation
- binary restore byte-perfect

---

## Phase 8 Acceptance Criteria

Diff output should be readable enough for a developer to inspect code changes before committing.

Restore must reproduce historical Blob contents exactly.

---

# PHASE 9 — Reliability, Integrity, Testing, and Repository Verification

## Objective

Transform PocketGit from a feature demo into a credible engineering project.

---

## 9.1 Verify Command

Implement:

```bash
pocketgit verify
```

It should examine repository integrity.

Checks should include:

- HEAD valid
- referenced branch exists
- branch commit exists
- commit object parses
- commit Tree exists
- Tree entries resolve
- Blob objects exist
- object hashes match content
- index parses
- index paths valid
- no illegal object filenames

Output:

```text
Checking repository...
✓ HEAD
✓ refs
✓ commits
✓ trees
✓ blobs
✓ index

Repository OK.
```

On corruption:

```text
ERROR object a72fc8... hash mismatch
ERROR commit d91c2a... references missing tree 889cc...
Repository integrity check failed.
```

---

## 9.2 Object Hash Verification

Read raw canonical object representation.

Recalculate SHA-256.

Ensure filename/hash matches calculated ID.

This catches deliberate or accidental object corruption.

---

## 9.3 Graph Verification

Traverse commits from all refs.

Maintain:

```java
Set<String> visited
Set<String> active
```

Validate graph.

No infinite traversal.

---

## 9.4 Failure Injection Tests

Test simulated problems:

- missing Blob
- missing Tree
- truncated Commit
- corrupt index
- malformed HEAD
- branch pointing to invalid hash
- object with wrong filename hash
- unwritable metadata directory

The application should fail clearly rather than throwing meaningless stack traces to normal CLI users.

---

## 9.5 Exception Architecture

Create purposeful exceptions, for example:

```java
PocketGitException
RepositoryNotFoundException
InvalidRepositoryException
ObjectNotFoundException
CorruptObjectException
InvalidRefException
CheckoutConflictException
IndexCorruptionException
```

CLI boundary catches expected domain exceptions.

Normal error:

```text
error: repository not found
```

Debug mode may expose stack trace.

Example:

```bash
pocketgit --debug status
```

---

## 9.6 Unit Test Target

Core business logic target:

```text
80%+ meaningful coverage
```

Do not game coverage with trivial tests.

Prioritize:

- object store
- index
- tree building
- commits
- refs
- status
- checkout planning
- diff
- restore
- integrity validation

---

## 9.7 Integration Tests

Create realistic workflows.

Scenario 1:

```text
init
add
commit
modify
status
diff
add
commit
log
```

Scenario 2:

```text
init
commit
create branch
modify main
commit
checkout branch
verify old content restored
```

Scenario 3:

```text
branch A
branch B
local modification
checkout would overwrite
ensure checkout blocked
ensure bytes unchanged
```

---

## 9.8 Property-Like Tests

Where practical, randomly generate:

- small directory structures
- file content
- nested paths

Assert:

```text
snapshot -> checkout/restore -> same bytes
```

Even without property-testing frameworks, repeated parameterized tests can add confidence.

---

## 9.9 Large Repository Test

Generate:

```text
1,000 files
nested directories
mixture of text and binary
```

Run:

```text
add
commit
status
```

The system does not need Git-level performance, but should remain usable.

Record benchmark results in documentation.

---

## 9.10 Concurrency Guard

Two simultaneous PocketGit commands could corrupt refs/index.

Implement a simple repository lock:

```text
.pocketgit/LOCK
```

or finer-grained lock files.

Requirements:

- atomic lock acquisition
- clear failure if already locked
- cleanup on normal exit
- stale lock recovery strategy documented

Do not overengineer full distributed locking.

---

## Phase 9 Acceptance Criteria

A deliberately corrupted repository should be detected, and ordinary command failures should produce clean actionable messages.

---

# PHASE 10 — Portfolio Polish, JavaFX History Viewer, Packaging, Documentation, and Release

## Objective

Turn the working engine into an impressive public portfolio project.

The CLI is the product.

The visualizer is a polished bonus.

---

## 10.1 README

Create a strong README containing:

```text
PocketGit
one-sentence description
demo GIF
feature list
architecture diagram
installation
quick start
repository format
example workflow
engineering decisions
testing
limitations
roadmap
```

Opening:

```text
PocketGit is a Git-inspired version-control engine written from scratch in Java.

It implements immutable content-addressable storage, SHA-256 object IDs,
staging, commit trees, branching, checkout safety, history traversal,
diff generation, restoration, and integrity verification without relying
on Git internally.
```

---

## 10.2 Architecture Diagram

Include something like:

```text
CLI
 │
 ▼
Command Layer
 │
 ▼
Service Layer
 │
 ├─────────────┬──────────────┬──────────────┐
 ▼             ▼              ▼              ▼
Index       Object Store     Refs          Diff Engine
 │             │              │
 ▼             ▼              ▼
Working      Blobs          HEAD
Tree         Trees          Branches
             Commits
```

Document command flow.

Example commit:

```text
Working Tree
    │
    │ add
    ▼
Index
    │
    │ commit
    ▼
Trees + Commit
    │
    ▼
Object Store
    │
    ▼
Branch Ref
```

---

## 10.3 Repository Format Documentation

Write:

```text
docs/repository-format.md
```

Document:

- HEAD
- refs
- object naming
- hashing payload
- Blob representation
- Tree representation
- Commit representation
- index
- ignore rules
- locks
- reflogs

A reviewer should be able to write an independent reader from the document.

---

## 10.4 Architecture Decision Records

Create several short ADRs.

Example:

```text
ADR-001: Use SHA-256 instead of SHA-1
ADR-002: Store deterministic Tree entries
ADR-003: Keep CLI separate from services
ADR-004: Use atomic index replacement
ADR-005: Block unsafe checkout operations
ADR-006: Use Myers/LCS diff
```

Each ADR:

```text
Context
Decision
Consequences
Alternatives
```

---

## 10.5 JavaFX Commit Visualizer

Create an optional desktop visualizer.

Launch:

```bash
pocketgit gui
```

or separate:

```bash
java -jar pocketgit-gui.jar
```

Initial scope:

- open current repository
- display current branch
- list branches
- render commit history
- select commit
- show metadata
- show changed files

Do not build a full Git GUI.

---

## 10.6 Visual Graph

Represent commits using nodes:

```text
● Add restore support
│
● Add diff engine
│
● Implement checkout safety
│
● Initial commit
```

With branches:

```text
● main: Improve status
│
● Base commit
|\
| ● feature: Experimental diff
| ● Prototype
|/
● Initial
```

If true branching graph layout becomes too large in scope, initially render:

- lane
- node
- parent connection
- branch label

Keep UI clean.

---

## 10.7 UI Quality

Avoid default-looking JavaFX controls everywhere.

Create a coherent minimal design:

```text
dark neutral background
high-contrast typography
subtle borders
compact developer-tool layout
monospace hashes
clear branch labels
```

Panels:

```text
┌─────────────────────────────────────────────────────┐
│ PocketGit │ main │ Repository: /projects/demo      │
├────────────┬─────────────────────┬──────────────────┤
│ Branches   │ Commit Graph        │ Commit Details   │
│            │                     │                  │
│ * main     │ ● d91c2a7          │ Add diff engine  │
│   feature  │ │                   │ Jane Developer   │
│            │ ● 52a8e91          │ 2026-09-29       │
│            │ │                   │                  │
│            │ ● 91dd310          │ Changed files    │
│            │                     │  4 modified      │
└────────────┴─────────────────────┴──────────────────┘
```

---

## 10.8 Distribution

Produce release artifacts.

At minimum:

```text
pocketgit.jar
```

Preferably:

```text
pocketgit-1.0.0.jar
```

Optionally use:

```text
jpackage
```

to produce native launchers.

Platforms may include:

```text
Windows .exe/.msi
macOS app/pkg
Linux package
```

Do not make native packaging a blocker for core release.

---

## 10.9 CI

Create GitHub Actions workflow.

On:

```text
push
pull_request
```

Run:

```text
compile
unit tests
integration tests
package
```

Test matrix if practical:

```text
Ubuntu
Windows
macOS
```

Java:

```text
21
```

---

## 10.10 Static Analysis

Add appropriate quality tooling.

Potential:

```text
SpotBugs
Checkstyle
PMD
```

Do not enable an enormous arbitrary rule set that creates meaningless noise.

Use quality tooling intentionally.

---

## 10.11 Demo Script

Create:

```text
examples/demo.sh
```

and optionally:

```text
examples/demo.ps1
```

Example workflow:

```bash
mkdir pocketgit-demo
cd pocketgit-demo

pocketgit init

echo "# Demo" > README.md

pocketgit add README.md
pocketgit commit -m "Initial commit"

echo "PocketGit is working." >> README.md

pocketgit status
pocketgit diff

pocketgit add README.md
pocketgit commit -m "Update README"

pocketgit branch experiment
pocketgit checkout experiment

echo "Experimental work" > experiment.txt
pocketgit add .
pocketgit commit -m "Add experiment"

pocketgit log --oneline
pocketgit verify
```

---

## 10.12 Screenshot / Recording

Create a polished terminal recording demonstrating:

```text
init
status
add
commit
diff
branch
checkout
log
verify
```

Keep it approximately:

```text
20–40 seconds
```

No typing mistakes.

No irrelevant terminal clutter.

---

# Final Engineering Requirements

The project is not complete until the following invariants hold.

## Object Integrity

For every stored object:

```text
hash(canonical object bytes) == object ID
```

---

## Commit Integrity

Every reachable Commit must reference:

```text
an existing Tree
```

Every Tree must reference:

```text
existing Trees or Blobs
```

---

## Index Integrity

Every index entry must contain:

```text
valid normalized path
valid Blob hash
```

---

## Ref Integrity

Every non-empty branch must point to:

```text
an existing Commit
```

---

## Checkout Safety

Checkout must never knowingly overwrite:

```text
unstaged modifications
conflicting untracked files
```

without explicit user authorization.

---

## Determinism

Given identical:

```text
file bytes
paths
tree structure
commit metadata
```

PocketGit must produce deterministic object IDs.

---

# UX Requirements

Use clean, short command output.

Good:

```text
Initialized empty PocketGit repository in /projects/demo/.pocketgit
```

Good:

```text
[main d91c2a7] Add diff engine
 4 files changed
```

Good error:

```text
error: not a PocketGit repository
```

Bad:

```text
java.lang.NullPointerException...
```

Normal users should not receive raw internal stack traces.

---

# Suggested CLI Examples

## Init

```bash
$ pocketgit init
Initialized empty PocketGit repository in /Users/jane/demo/.pocketgit
```

## Add

```bash
$ pocketgit add .
Staged 7 files.
```

## Status

```bash
$ pocketgit status
On branch main

Changes to be committed:
  modified: src/main/java/com/pocketgit/Repository.java

Untracked files:
  notes.txt
```

## Commit

```bash
$ pocketgit commit -m "Implement repository discovery"
[main 82ef231] Implement repository discovery
 3 files changed
```

## Log

```bash
$ pocketgit log --oneline
82ef231 Implement repository discovery
08c991f Add CLI skeleton
6b130ca Initial commit
```

## Branch

```bash
$ pocketgit branch
* main
  experiment
```

## Checkout

```bash
$ pocketgit checkout experiment
Switched to branch 'experiment'
```

## Verify

```bash
$ pocketgit verify
Checking repository...
✓ HEAD
✓ refs
✓ commits
✓ trees
✓ blobs
✓ index

Repository OK.
```

---

# Testing Matrix

Before declaring version 1.0 complete, verify all of the following.

| Area | Required |
|---|---|
| Init | Yes |
| Repository discovery | Yes |
| Blob storage | Yes |
| Tree storage | Yes |
| Commit storage | Yes |
| SHA-256 validation | Yes |
| Add single file | Yes |
| Add directory | Yes |
| Add all | Yes |
| Delete staging | Yes |
| Ignore rules | Yes |
| First commit | Yes |
| Parent commits | Yes |
| Status staged | Yes |
| Status unstaged | Yes |
| Status untracked | Yes |
| Log | Yes |
| Branch creation | Yes |
| Branch listing | Yes |
| Checkout | Yes |
| Checkout conflict protection | Yes |
| Text diff | Yes |
| Binary detection | Yes |
| Restore | Yes |
| Integrity verification | Yes |
| Corruption tests | Yes |
| Windows | Yes |
| macOS/Linux | Yes where CI permits |

---

# Development Discipline

After each phase:

1. run compilation
2. run unit tests
3. run integration tests
4. inspect newly created `.pocketgit` files manually
5. execute a real CLI workflow
6. fix failures before continuing
7. update documentation where storage behavior changed
8. commit the phase independently

Recommended commit sequence:

```text
phase-1: initialize project and repository structure
phase-2: implement immutable object database
phase-3: implement staging index and add
phase-4: implement trees and commits
phase-5: implement repository status
phase-6: implement history and branches
phase-7: implement safe checkout
phase-8: implement diff and restore
phase-9: add integrity checks and reliability
phase-10: polish documentation, GUI, CI, and release
```

---

# Explicit Non-Goals for Version 1.0

Do not allow these to derail the project:

```text
remote repositories
push/pull
network protocols
Git wire protocol
GitHub integration
merge conflict resolution
interactive rebase
cherry-pick
submodules
Git LFS
signed commits
server hosting
distributed collaboration
full Git compatibility
```

These can form a future roadmap.

---

# Potential Version 2 Features

After the core project is genuinely solid:

```text
merge
three-way merge
merge conflicts
tags
stash
commit amend
reset
reflog recovery
garbage collection
pack files
remote synchronization
HTTP server
GitHub-like repository viewer
```

---

# Final Definition of Done

PocketGit 1.0 is complete only when a clean machine can perform:

```bash
pocketgit init

echo "hello" > hello.txt

pocketgit add .
pocketgit commit -m "Initial commit"

echo "world" >> hello.txt

pocketgit status
pocketgit diff

pocketgit add .
pocketgit commit -m "Update hello"

pocketgit branch experiment
pocketgit checkout experiment

echo "branch work" > experiment.txt
pocketgit add .
pocketgit commit -m "Experiment"

pocketgit checkout main

pocketgit log --oneline

pocketgit restore --commit <old-hash> hello.txt

pocketgit verify
```

and every operation behaves correctly.

The repository must have:

- a professional README
- architecture documentation
- repository-format documentation
- automated tests
- CI
- clean error handling
- meaningful commit history
- deterministic object storage
- safe checkout behavior
- no dependency on the real Git implementation

The finished result should feel like a **small real developer tool**, not a classroom CRUD assignment.

The strongest portfolio story is:

> “I built a version-control engine from scratch in Java to understand how immutable object databases, snapshots, branches, commit graphs, working-tree reconciliation, diff algorithms, and repository integrity work under the hood.”

That is the engineering standard to maintain throughout all ten phases.
