package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.pocketgit.model.Blob;
import com.pocketgit.model.ObjectType;
import com.pocketgit.repository.InvalidRepositoryException;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.storage.CorruptObjectException;
import com.pocketgit.storage.ObjectHasher;
import com.pocketgit.storage.ObjectNotFoundException;
import com.pocketgit.storage.ObjectStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ObjectStoreTest {
    @TempDir Path temp;
    private Repository repo;
    private ObjectStore store;
    private final ObjectHasher hasher = new ObjectHasher();

    @BeforeEach
    void initialize() throws Exception {
        repo = new RepositoryInitializer().initialize(temp).repository();
        store = new ObjectStore(repo);
    }

    @ParameterizedTest
    @EnumSource(ObjectType.class)
    void allTypesRoundTripAndSurviveStoreRestart(ObjectType type) throws Exception {
        byte[] payload = new byte[] {0, -1, 42, 10, -128};
        String id = store.write(type, payload);
        var object = new ObjectStore(repo).read(id);
        assertEquals(type, object.type());
        assertEquals(payload.length, object.size());
        assertArrayEquals(payload, object.payload());
        assertEquals(id, hasher.hash(type, object.payload()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 1024, 1048576})
    void blobsRoundTripEmptyBinaryAndOneMegabyte(int length) throws Exception {
        byte[] bytes = new byte[length];
        new Random(42).nextBytes(bytes);
        String id = store.writeBlob(new Blob(bytes));
        assertArrayEquals(bytes, store.readBlob(id).content());
        Path path = store.pathForHash(id);
        assertEquals(
                repo.objectsDirectory().resolve(id.substring(0, 2)).resolve(id.substring(2)), path);
        try (var input = new InflaterInputStream(Files.newInputStream(path))) {
            assertArrayEquals(hasher.canonicalBytes(ObjectType.BLOB, bytes), input.readAllBytes());
        }
        assertNoTemporaryFiles();
    }

    @Test
    void duplicateWritesPreserveStoredBytesAndTimestamp() throws Exception {
        byte[] payload = "duplicate".getBytes(StandardCharsets.UTF_8);
        String id = store.write(ObjectType.BLOB, payload);
        Path path = store.pathForHash(id);
        byte[] stored = Files.readAllBytes(path);
        var timestamp = Files.getLastModifiedTime(path);
        assertEquals(id, store.write(ObjectType.BLOB, payload));
        assertArrayEquals(stored, Files.readAllBytes(path));
        assertEquals(timestamp, Files.getLastModifiedTime(path));
        try (var files = Files.walk(repo.objectsDirectory())) {
            assertEquals(1, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void detectsMissingPrefixAndMissingObject() throws Exception {
        String hash = "0".repeat(64);
        assertThrows(ObjectNotFoundException.class, () -> store.read(hash));
        Files.createDirectory(repo.objectsDirectory().resolve("00"));
        assertThrows(ObjectNotFoundException.class, () -> store.read(hash));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "abcd",
                "../outside",
                "A000000000000000000000000000000000000000000000000000000000000000",
                "000000000000000000000000000000000000000000000000000000000000000g"
            })
    void rejectsMalformedIdsBeforeFilesystemAccess(String id) {
        assertThrows(IllegalArgumentException.class, () -> store.pathForHash(id));
        assertThrows(IllegalArgumentException.class, () -> store.read(id));
    }

    @Test
    void rejectsNullId() {
        assertThrows(IllegalArgumentException.class, () -> store.read(null));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "blob 4\0abc",
                "blob 2\0abc",
                "blob -1\0",
                "blob 01\0a",
                "blob 1 extra\0a",
                "blob 1a",
                "blob 9999999999999999999999999\0a",
                "weird 1\0a",
                "blob 1\0"
            })
    void rejectsInvalidCanonicalHeadersAndLengths(String malformed) throws Exception {
        byte[] canonical = malformed.getBytes(StandardCharsets.UTF_8);
        String hash = hasher.hashCanonical(canonical);
        writeFixture(hash, compress(canonical));
        assertThrows(CorruptObjectException.class, () -> store.read(hash));
    }

    @Test
    void reportsDistinctUnknownTypeAndLengthErrors() throws Exception {
        String id = "0".repeat(64);
        writeFixture(id, compress("unknown 0\0".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(
                assertThrows(CorruptObjectException.class, () -> store.read(id))
                        .getMessage()
                        .startsWith("unknown object type"));
        writeFixture(id, compress("blob 5\0ab".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(
                assertThrows(CorruptObjectException.class, () -> store.read(id))
                        .getMessage()
                        .contains("payload length mismatch"));
    }

    @Test
    void rejectsLongAndNonAsciiHeaders() throws Exception {
        for (byte[] canonical :
                new byte[][] {
                    ("b".repeat(129) + " 0\0").getBytes(), new byte[] {-1, ' ', '0', 0}
                }) {
            String id = hasher.hashCanonical(canonical);
            writeFixture(id, compress(canonical));
            assertThrows(CorruptObjectException.class, () -> store.read(id));
        }
    }

    @Test
    void rejectsHashMismatchAndNeverRepairsCorruptExistingObject() throws Exception {
        byte[] requested = {1, 2, 3};
        String id = hasher.hash(ObjectType.BLOB, requested);
        byte[] corrupt = compress(hasher.canonicalBytes(ObjectType.BLOB, new byte[] {9, 8, 7}));
        writeFixture(id, corrupt);
        assertTrue(
                assertThrows(CorruptObjectException.class, () -> store.read(id))
                        .getMessage()
                        .contains("hash mismatch"));
        assertThrows(CorruptObjectException.class, () -> store.write(ObjectType.BLOB, requested));
        assertArrayEquals(corrupt, Files.readAllBytes(store.pathForHash(id)));
        assertNoTemporaryFiles();
    }

    @Test
    void rejectsDamagedTruncatedTrailingAndConcatenatedCompression() throws Exception {
        byte[] canonical = hasher.canonicalBytes(ObjectType.BLOB, new byte[] {42});
        String id = hasher.hashCanonical(canonical);
        byte[] compressed = compress(canonical);
        byte[] trailing = java.util.Arrays.copyOf(compressed, compressed.length + 1);
        byte[] concatenated = new byte[compressed.length * 2];
        System.arraycopy(compressed, 0, concatenated, 0, compressed.length);
        System.arraycopy(compressed, 0, concatenated, compressed.length, compressed.length);
        for (byte[] invalid :
                new byte[][] {
                    new byte[] {1, 2, 3},
                    new byte[0],
                    java.util.Arrays.copyOf(compressed, compressed.length - 2),
                    trailing,
                    concatenated
                }) {
            writeFixture(id, invalid);
            assertThrows(CorruptObjectException.class, () -> store.read(id));
        }
    }

    @Test
    void payloadLimitsBoundBothWritesAndInflation() throws Exception {
        ObjectStore small = new ObjectStore(repo, 16);
        assertThrows(IOException.class, () -> small.write(ObjectType.BLOB, new byte[17]));
        String id = store.write(ObjectType.BLOB, new byte[2048]);
        assertThrows(CorruptObjectException.class, () -> small.read(id));
        String declared = hasher.hashCanonical("blob 17\0".getBytes());
        writeFixture(declared, compress("blob 17\0".getBytes()));
        assertThrows(CorruptObjectException.class, () -> small.read(declared));
        assertThrows(IllegalArgumentException.class, () -> new ObjectStore(repo, -1));
        assertNoTemporaryFiles();
    }

    @Test
    void readBlobRejectsOtherObjectTypes() throws Exception {
        String id = store.write(ObjectType.TREE, new byte[0]);
        assertThrows(IOException.class, () -> store.readBlob(id));
    }

    @Test
    void concurrentWritersPublishOneCompleteObjectAndCleanTemporaryFiles() throws Exception {
        byte[] payload = new byte[1024 * 1024];
        new Random(17).nextBytes(payload);
        var executor = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 12; i++)
                futures.add(
                        executor.submit(
                                () -> new ObjectStore(repo).write(ObjectType.BLOB, payload)));
            String expected = hasher.hash(ObjectType.BLOB, payload);
            for (var future : futures) assertEquals(expected, future.get(20, TimeUnit.SECONDS));
            assertArrayEquals(payload, store.readBlob(expected).content());
            try (var files = Files.walk(repo.objectsDirectory())) {
                assertEquals(1, files.filter(Files::isRegularFile).count());
            }
        } finally {
            executor.shutdownNow();
        }
        assertNoTemporaryFiles();
    }

    @Test
    void rejectsSymlinkedObjectLeafWithoutReadingOrOverwritingTarget() throws Exception {
        byte[] payload = {1};
        String id = hasher.hash(ObjectType.BLOB, payload);
        Files.createDirectory(store.pathForHash(id).getParent());
        Path target = Files.writeString(temp.resolve("outside"), "keep");
        symlinkOrSkip(store.pathForHash(id), target);
        assertThrows(CorruptObjectException.class, () -> store.read(id));
        assertThrows(CorruptObjectException.class, () -> store.write(ObjectType.BLOB, payload));
        assertEquals("keep", Files.readString(target));
    }

    @Test
    void rejectsSymlinkedPrefixAndObjectsDirectory() throws Exception {
        String id = hasher.hash(ObjectType.BLOB, new byte[0]);
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path prefix = store.pathForHash(id).getParent();
        symlinkOrSkip(prefix, outside);
        assertThrows(
                InvalidRepositoryException.class, () -> store.write(ObjectType.BLOB, new byte[0]));
        assertThrows(InvalidRepositoryException.class, () -> store.read(id));
        Files.delete(prefix);
        Files.delete(repo.objectsDirectory());
        symlinkOrSkip(repo.objectsDirectory(), outside);
        assertThrows(InvalidRepositoryException.class, () -> new ObjectStore(repo));
        try (var contents = Files.list(outside)) {
            assertEquals(0, contents.count());
        }
    }

    @Test
    void refusesPrefixFileAndDoesNotAlterIt() throws Exception {
        String id = hasher.hash(ObjectType.BLOB, new byte[0]);
        Path prefix = store.pathForHash(id).getParent();
        Files.writeString(prefix, "preserve");
        assertThrows(
                InvalidRepositoryException.class, () -> store.write(ObjectType.BLOB, new byte[0]));
        assertEquals("preserve", Files.readString(prefix));
    }

    private byte[] compress(byte[] canonical) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DeflaterOutputStream(bytes)) {
            output.write(canonical);
        }
        return bytes.toByteArray();
    }

    private void writeFixture(String hash, byte[] compressed) throws IOException {
        Path path = store.pathForHash(hash);
        Files.createDirectories(path.getParent());
        Files.write(path, compressed);
    }

    private void assertNoTemporaryFiles() throws IOException {
        try (var files = Files.walk(repo.objectsDirectory())) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".tmp-")));
        }
    }

    private void symlinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException unavailable) {
            assumeTrue(false, "Symlinks unavailable: " + unavailable.getMessage());
        }
    }
}
