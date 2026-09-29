package com.pocketgit.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketgit.repository.InvalidRepositoryException;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryInitializer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RepositoryInitializerTest {
    @TempDir Path temp;
    private final RepositoryInitializer initializer = new RepositoryInitializer();

    @Test void createsCompleteStructureAndInitialValues() throws Exception {
        var result = initializer.initialize(temp);
        assertTrue(result.created());
        Repository repo = result.repository();
        for (Path directory : java.util.List.of(repo.objectsDirectory(), repo.refsDirectory(),
                repo.headsDirectory(), repo.logsDirectory())) assertTrue(Files.isDirectory(directory));
        assertEquals("ref: refs/heads/main\n", Files.readString(repo.headFile()));
        assertEquals(0, Files.size(repo.mainRefFile()));
        var index = new ObjectMapper().readTree(repo.indexFile().toFile());
        assertEquals(1, index.get("version").asInt());
        assertTrue(index.get("entries").isArray());
        assertTrue(index.get("entries").isEmpty());
        assertFalse(Files.exists(repo.configFile()));
    }

    @Test void repeatedInitPreservesEveryFileAndWorkingTree() throws Exception {
        Repository repo = initializer.initialize(temp).repository();
        Files.writeString(repo.headFile(), "ref: refs/heads/feature\n");
        Files.delete(repo.mainRefFile());
        Files.writeString(repo.headsDirectory().resolve("feature"), "historical ref");
        Files.writeString(repo.indexFile(), "custom index contents");
        Files.createDirectory(repo.objectsDirectory().resolve("ab"));
        Files.write(repo.objectsDirectory().resolve("ab/object"), new byte[]{0, 1, -1});
        Files.writeString(repo.configFile(), "user config");
        Files.writeString(repo.logsDirectory().resolve("HEAD"), "history");
        Files.writeString(temp.resolve("notes.txt"), "precious work");
        Map<String, String> before = snapshot(temp);
        assertFalse(initializer.initialize(temp).created());
        assertEquals(before, snapshot(temp));
    }

    @ParameterizedTest @ValueSource(strings = {"directory with spaces", "پروژه 日本語 café"})
    void supportsPortableNames(String name) throws Exception {
        Path root = Files.createDirectory(temp.resolve(name));
        var result = initializer.initialize(root.resolve("."));
        assertEquals(root.toRealPath(), result.repository().root());
        assertTrue(Files.isRegularFile(result.repository().headFile()));
    }

    @Test void rejectsMetadataFileWithoutOverwritingIt() throws Exception {
        Path marker = temp.resolve(".pocketgit");
        Files.writeString(marker, "keep me");
        assertThrows(InvalidRepositoryException.class, () -> initializer.initialize(temp));
        assertEquals("keep me", Files.readString(marker));
    }

    @Test void rejectsIncompleteMetadataWithoutRepairingOrDeletingIt() throws Exception {
        Path marker = Files.createDirectory(temp.resolve(".pocketgit"));
        Files.writeString(marker.resolve("HEAD"), "original");
        Map<String, String> before = snapshot(temp);
        assertThrows(InvalidRepositoryException.class, () -> initializer.initialize(temp));
        assertEquals(before, snapshot(temp));
    }

    @Test void rejectsMissingTargetWithoutCreatingDirectories() {
        Path absent = temp.resolve("missing");
        assertThrows(IOException.class, () -> initializer.initialize(absent));
        assertFalse(Files.exists(absent));
    }

    @Test void rejectsFileTarget() throws Exception {
        Path file = Files.writeString(temp.resolve("file"), "bytes");
        assertThrows(IOException.class, () -> initializer.initialize(file));
        assertEquals("bytes", Files.readString(file));
    }

    @Test void rejectsMetadataSymlinkWithoutTouchingItsDestination() throws Exception {
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path root = Files.createDirectory(temp.resolve("root"));
        Files.writeString(outside.resolve("keep.txt"), "safe");
        createSymlinkOrSkip(root.resolve(".pocketgit"), outside);
        assertThrows(InvalidRepositoryException.class, () -> initializer.initialize(root));
        assertEquals("safe", Files.readString(outside.resolve("keep.txt")));
        assertFalse(Files.exists(outside.resolve("HEAD")));
    }

    @Test void rejectsSymlinkWithinExistingMetadata() throws Exception {
        Repository repo = initializer.initialize(temp).repository();
        Path outside = Files.writeString(temp.resolve("outside"), "safe");
        Files.delete(repo.headFile());
        createSymlinkOrSkip(repo.headFile(), outside);
        assertThrows(InvalidRepositoryException.class, () -> initializer.initialize(temp));
        assertEquals("safe", Files.readString(outside));
    }

    private void createSymlinkOrSkip(Path link, Path target) throws IOException {
        try { Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException | IOException unavailable) {
            assumeTrue(false, "Symlinks unavailable: " + unavailable.getMessage());
        }
    }

    private Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(path).toString(), java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
            }
        }
        return files;
    }
}
