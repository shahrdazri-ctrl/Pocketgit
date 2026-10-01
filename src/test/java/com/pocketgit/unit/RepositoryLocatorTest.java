package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.pocketgit.repository.InvalidRepositoryException;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.repository.RepositoryLocator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RepositoryLocatorTest {
    @TempDir Path temp;
    private final RepositoryLocator locator = new RepositoryLocator();

    @Test
    void findsRootAndDeeplyNestedDirectory() throws Exception {
        new RepositoryInitializer().initialize(temp);
        Path nested = Files.createDirectories(temp.resolve("src/main/java/com/pocketgit"));
        assertEquals(temp.toRealPath(), locator.findRepositoryRoot(temp).orElseThrow());
        assertEquals(temp.toRealPath(), locator.findRepositoryRoot(nested).orElseThrow());
    }

    @Test
    void returnsEmptyOutsideRepositoryAndTerminatesAtFilesystemRoot() throws Exception {
        assertTrue(locator.findRepositoryRoot(temp).isEmpty());
        assertTrue(locator.findRepositoryRoot(temp.toAbsolutePath().getRoot()).isEmpty());
    }

    @Test
    void choosesNearestRepository() throws Exception {
        new RepositoryInitializer().initialize(temp);
        Path inner = Files.createDirectory(temp.resolve("inner"));
        new RepositoryInitializer().initialize(inner);
        assertEquals(inner.toRealPath(), locator.findRepositoryRoot(inner).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"with spaces", "مخزن 日本語"})
    void supportsSpacesUnicodeAndNormalizedPaths(String name) throws Exception {
        Path root = Files.createDirectory(temp.resolve(name));
        new RepositoryInitializer().initialize(root);
        Path child = Files.createDirectory(root.resolve("child"));
        assertEquals(
                root.toRealPath(), locator.findRepositoryRoot(child.resolve("..")).orElseThrow());
    }

    @Test
    void rejectsInvalidMarkerRatherThanSkippingToOuterRepository() throws Exception {
        new RepositoryInitializer().initialize(temp);
        Path inner = Files.createDirectory(temp.resolve("inner"));
        Files.writeString(inner.resolve(".pocketgit"), "not a directory");
        assertThrows(InvalidRepositoryException.class, () -> locator.findRepositoryRoot(inner));
    }

    @Test
    void rejectsMissingOrFileStartingPath() throws Exception {
        assertThrows(IOException.class, () -> locator.findRepositoryRoot(temp.resolve("missing")));
        Path file = Files.writeString(temp.resolve("file"), "x");
        assertThrows(IOException.class, () -> locator.findRepositoryRoot(file));
    }

    @Test
    void followsWorkingDirectoryAliasButRejectsMetadataAlias() throws Exception {
        Path root = Files.createDirectory(temp.resolve("physical"));
        new RepositoryInitializer().initialize(root);
        Path alias = temp.resolve("alias");
        try {
            Files.createSymbolicLink(alias, root);
        } catch (IOException | UnsupportedOperationException unavailable) {
            assumeTrue(false, "Symlinks unavailable: " + unavailable.getMessage());
        }
        assertEquals(root.toRealPath(), locator.findRepositoryRoot(alias).orElseThrow());
        Path other = Files.createDirectory(temp.resolve("other"));
        Files.createSymbolicLink(other.resolve(".pocketgit"), root.resolve(".pocketgit"));
        assertThrows(InvalidRepositoryException.class, () -> locator.findRepositoryRoot(other));
    }

    @Test
    void repositoryAbstractionNormalizesRelativeRoot() {
        Repository repo = new Repository(Path.of("relative", "child", ".."));
        assertTrue(repo.root().isAbsolute());
        assertEquals(Path.of("relative").toAbsolutePath().normalize(), repo.root());
        assertEquals(repo.metadataDirectory().resolve("HEAD"), repo.headFile());
    }
}
