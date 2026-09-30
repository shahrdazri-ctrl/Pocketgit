# Object database API

The Java object layer is usable independently of Picocli. It never invokes Git or external compression programs.

```java
import com.pocketgit.model.Blob;
import com.pocketgit.model.ObjectType;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.storage.ObjectStore;
import java.nio.file.Files;
import java.nio.file.Path;

Path directory = Path.of("demo"); // Existing working-tree directory.
Repository repository = new RepositoryInitializer().initialize(directory).repository();
ObjectStore objects = new ObjectStore(repository);
byte[] source = Files.readAllBytes(directory.resolve("photo.png"));
String id = objects.writeBlob(new Blob(source));
Blob restored = objects.readBlob(id);
assert java.util.Arrays.equals(source, restored.content());
System.out.println(id);
```

`write(ObjectType, byte[])` handles the generic Blob/Tree/Commit envelope. `read(id)` returns verified `StoredObject` values with `type()`, `size()`, and a defensive-copy `payload()`. `readBlob(id)`, `readTree(id)`, and `readCommit(id)` reject other types before retaining their payload. `pathForHash(id)` returns the deterministic storage location and validates the ID; it does not write files.

Use `ObjectHasher.hash(type, payload)` to calculate an ID without a repository. Use `canonicalBytes(type, payload)` to inspect the exact hashing input. `Blob` and `StoredObject` copy inputs and accessor results, protecting stored values from caller mutation.

Errors distinguish missing objects (`ObjectNotFoundException`), malformed/corrupt objects (`CorruptObjectException`), invalid metadata (`InvalidRepositoryException`), malformed IDs (`IllegalArgumentException`), and ordinary filesystem failures (`IOException`). The CLI maps execution failures to a short error and exit code 1.

For large Blobs, use the streaming APIs instead of the retaining example above:

```java
Path sourceFile = directory.resolve("photo.png");
String streamedId;
try (var input = Files.newInputStream(sourceFile)) {
    streamedId = objects.writeBlob(Files.size(sourceFile), input);
}
var summary = objects.verifyBlob(streamedId); // Type, size, zlib envelope, and hash.
Path extracted = Files.createTempFile("pocketgit-extract-", ".bin");
try (var output = Files.newOutputStream(extracted)) {
    objects.copyBlob(streamedId, output);
}
assert Files.mismatch(sourceFile, extracted) == -1;
```

`writeBlob(size, input)` requires exactly the declared bytes, enforces the payload limit, and cleans private compressed preparation after errors. `verify(id)` returns type/size after streaming full validation without retaining a payload. `copyBlob` and `copy(id, expectedType, output)` stream while validating. The caller owns both streams. Keep copied bytes private until the method succeeds: a late corruption or sink error can leave partial output. Publish a prepared output file only after success. Canonical IDs and immutable exclusive publication are unchanged.

`cat-object` is read-only. Default mode validates once before emitting any bytes, then streams the exact payload with no added newline, suitable for redirection. Type/size modes use full streaming verification. Pretty inspection accepts at most 8 MiB, preserves LF/tab layout, escapes terminal controls as `\uXXXX` (including carriage returns), and adds no newline to a Blob. It rejects NUL or invalid UTF-8. Trees/Commits are semantically validated and formatted as JSON; use `show` for commit metadata. Raw mode provides byte-exact extraction. An external object mutation or output failure during the second read can still leave partial raw output; store files are immutable during normal PocketGit operations.
