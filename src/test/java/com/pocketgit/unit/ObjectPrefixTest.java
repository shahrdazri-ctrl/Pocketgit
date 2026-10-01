package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.pocketgit.model.Blob;
import com.pocketgit.repository.*;
import com.pocketgit.storage.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ObjectPrefixTest {
    @TempDir Path root;
    private Repository repo;
    private ObjectStore objects;

    @BeforeEach
    void initialize() throws Exception {
        repo = new RepositoryInitializer().initialize(root).repository();
        objects = new ObjectStore(repo);
    }

    @Test
    void uniqueAndFullIdsResolveAndUnknownIdsFail() throws Exception {
        String hash = objects.writeBlob(new Blob(new byte[] {1, 2, 3}));
        for (int length : new int[] {1, 2, 7, 63, 64})
            assertEquals(hash, objects.resolve(hash.substring(0, length)));
        String missing = (hash.charAt(0) == 'a' ? "b" : "a").repeat(64);
        assertThrows(ObjectNotFoundException.class, () -> objects.resolve(missing));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "../",
                "ABC",
                "g",
                "a/b",
                " a",
                "a\n",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            })
    void invalidPrefixesAreRejected(String prefix) {
        assertThrows(IOException.class, () -> objects.resolve(prefix));
    }

    @Test
    void ambiguousPrefixesFailAcrossActualStoredObjects() throws Exception {
        var seen = new HashMap<String, String>();
        for (int i = 0; i < 17; i++) {
            String hash =
                    objects.writeBlob(new Blob(("payload-" + i).getBytes(StandardCharsets.UTF_8)));
            String prefix = hash.substring(0, 1);
            if (seen.putIfAbsent(prefix, hash) != null) {
                assertTrue(
                        assertThrows(IOException.class, () -> objects.resolve(prefix))
                                .getMessage()
                                .contains("ambiguous"));
                assertEquals(hash, objects.resolve(hash));
                return;
            }
        }
        fail("17 distinct objects must share at least one of 16 first hex digits");
    }

    @Test
    void resolutionVerifiesMatchedBytesAndRejectsSymlinkedObjects() throws Exception {
        String hash = objects.writeBlob(new Blob(new byte[] {9}));
        Path path = objects.pathForHash(hash);
        Files.writeString(path, "corrupt");
        assertThrows(IOException.class, () -> objects.resolve(hash.substring(0, 7)));
        Files.delete(path);
        Path outside = Files.writeString(root.resolve("outside"), "private");
        try {
            Files.createSymbolicLink(path, outside);
        } catch (UnsupportedOperationException | IOException restricted) {
            assumeTrue(false, "symlinks unavailable");
        }
        assertThrows(IOException.class, () -> objects.resolve(hash));
        assertEquals("private", Files.readString(outside));
    }
}
