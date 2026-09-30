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

`write(ObjectType, byte[])` handles the generic Blob/Tree/Commit envelope. `read(id)` returns verified `StoredObject` values with `type()`, `size()`, and a defensive-copy `payload()`. `readBlob(id)` rejects other types. `pathForHash(id)` returns the deterministic storage location and validates the ID; it does not write files.

Use `ObjectHasher.hash(type, payload)` to calculate an ID without a repository. Use `canonicalBytes(type, payload)` to inspect the exact hashing input. `Blob` and `StoredObject` copy inputs and accessor results, protecting stored values from caller mutation.

Errors distinguish missing objects (`ObjectNotFoundException`), malformed/corrupt objects (`CorruptObjectException`), invalid metadata (`InvalidRepositoryException`), malformed IDs (`IllegalArgumentException`), and ordinary filesystem failures (`IOException`). The CLI maps execution failures to a short error and exit code 1.

`cat-object` is read-only. Default mode emits the verified payload directly to stdout with no added newline, suitable for redirection. Metadata modes print a single line; `--pretty` preserves UTF-8 text contents and line endings without appending a newline. It rejects NUL or invalid UTF-8 rather than decoding arbitrary binary bytes as text. Tree/Commit payloads are displayed in their canonical text format; use `show` for formatted commit metadata.
