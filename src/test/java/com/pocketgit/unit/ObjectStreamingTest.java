package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketgit.model.ObjectType;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.storage.ObjectStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

class ObjectStreamingTest {
    @TempDir Path root;
    private Repository repository;
    private ObjectStore store;

    @BeforeEach
    void initialize() throws Exception {
        repository = new RepositoryInitializer().initialize(root).repository();
        store = new ObjectStore(repository);
    }

    @Test
    void streamedBytesPreserveCanonicalIdsAndExactPayloads() throws Exception {
        for (int size : new int[] {0, 1, 8191, 8192, 8193, 100_000}) {
            byte[] bytes = new byte[size];
            new Random(size).nextBytes(bytes);
            String expected = store.write(ObjectType.BLOB, bytes);
            assertEquals(expected, store.writeBlob(size, new ByteArrayInputStream(bytes)));
            assertEquals(size, store.verifyBlob(expected).size());
            var copied = new ByteArrayOutputStream();
            store.copyBlob(expected, copied);
            assertArrayEquals(bytes, copied.toByteArray());
            assertArrayEquals(bytes, store.read(expected).payload());
        }
    }

    @Test
    void shortLongOversizedAndBrokenInputsPublishNothingAndCleanTemporaryFiles() throws Exception {
        assertThrows(
                IOException.class, () -> store.writeBlob(2, new ByteArrayInputStream(new byte[1])));
        assertThrows(
                IOException.class, () -> store.writeBlob(1, new ByteArrayInputStream(new byte[2])));
        assertThrows(IOException.class, () -> store.writeBlob(-1, InputStream.nullInputStream()));
        assertThrows(
                IOException.class,
                () -> new ObjectStore(repository, 1).writeBlob(2, InputStream.nullInputStream()));
        assertThrows(
                IOException.class,
                () ->
                        store.writeBlob(
                                2,
                                new InputStream() {
                                    @Override
                                    public int read() throws IOException {
                                        throw new IOException("injected read failure");
                                    }
                                }));
        try (var paths = Files.list(repository.objectsDirectory())) {
            assertEquals(0, paths.count());
        }
    }

    @Test
    void streamsRemainOwnedByTheCaller() throws Exception {
        var input =
                new ByteArrayInputStream(new byte[] {1, 2, 3}) {
                    @Override
                    public void close() {
                        fail("caller owns input");
                    }
                };
        String hash = store.writeBlob(3, input);
        var output =
                new ByteArrayOutputStream() {
                    @Override
                    public void close() {
                        fail("caller owns output");
                    }
                };
        store.copyBlob(hash, output);
        assertArrayEquals(new byte[] {1, 2, 3}, output.toByteArray());
    }

    @Test
    void corruptedExistingObjectsCannotBeSilentlyReused() throws Exception {
        byte[] bytes = new byte[] {1, 2, 3};
        String hash = store.writeBlob(3, new ByteArrayInputStream(bytes));
        Files.writeString(store.pathForHash(hash), "corrupt");
        assertThrows(IOException.class, () -> store.verify(hash));
        assertThrows(
                IOException.class, () -> store.copyBlob(hash, OutputStream.nullOutputStream()));
        assertThrows(IOException.class, () -> store.writeBlob(3, new ByteArrayInputStream(bytes)));
        assertEquals("corrupt", Files.readString(store.pathForHash(hash)));
        try (var paths = Files.list(repository.objectsDirectory())) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".tmp-")));
        }
    }

    @Test
    void typedReadsRejectWrongTypesBeforeRetainingPayloads() throws Exception {
        String blob = store.writeBlob(1, new ByteArrayInputStream(new byte[] {1}));
        assertTrue(
                assertThrows(IOException.class, () -> store.readCommit(blob))
                        .getMessage()
                        .contains("expected commit"));
        assertTrue(
                assertThrows(IOException.class, () -> store.readTree(blob))
                        .getMessage()
                        .contains("expected tree"));
        String tree = store.write(ObjectType.TREE, new byte[] {1});
        assertThrows(IOException.class, () -> store.readBlob(tree));
    }

    @Test
    void copyRejectsWrongTypesAndPreservesSinkErrors() throws Exception {
        String tree = store.write(ObjectType.TREE, new byte[] {1});
        var output = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> store.copyBlob(tree, output));
        assertEquals(0, output.size());
        String blob = store.writeBlob(1, new ByteArrayInputStream(new byte[] {1}));
        var error =
                assertThrows(
                        java.nio.file.NoSuchFileException.class,
                        () ->
                                store.copyBlob(
                                        blob,
                                        new OutputStream() {
                                            @Override
                                            public void write(int value) throws IOException {
                                                throw new java.nio.file.NoSuchFileException("sink");
                                            }
                                        }));
        assertEquals("sink", error.getFile());
    }
}
