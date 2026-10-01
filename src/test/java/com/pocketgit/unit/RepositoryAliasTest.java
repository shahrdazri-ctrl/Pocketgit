package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.services.AddService;
import com.pocketgit.services.RestoreService;
import com.pocketgit.storage.IndexStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryAliasTest {
    @TempDir Path temporary;

    private Path alias(Path target, String name) throws Exception {
        Path alias = temporary.resolve(name);
        try {
            Files.createSymbolicLink(alias, target);
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            assumeTrue(false, "symlinks unavailable");
        }
        return alias;
    }

    @Test
    void restoreAndAbsoluteAddAcceptAnAliasOfTheRepositoryRoot() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("physical"));
        var repository = new RepositoryInitializer().initialize(root).repository();
        Path logical = alias(root, "logical");
        Files.writeString(root.resolve("file"), "original");
        new AddService().add(logical, logical.resolve("file"));
        byte[] index = Files.readAllBytes(repository.indexFile());
        Files.writeString(root.resolve("file"), "edited");
        new RestoreService().restore(logical, Path.of("file"), null);
        assertEquals("original", Files.readString(root.resolve("file")));
        assertArrayEquals(index, Files.readAllBytes(repository.indexFile()));
        assertEquals("file", new IndexStore(repository).load().entries().getFirst().path());
    }

    @Test
    void aliasResolutionDoesNotFollowInteriorSymlinksOrCancelledLinks() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("physical"));
        var repository = new RepositoryInitializer().initialize(root).repository();
        Path logical = alias(root, "logical");
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(root.resolve("file"), "tracked");
        new AddService().add(root, Path.of("file"));
        Files.createSymbolicLink(root.resolve("escape"), outside);
        byte[] index = Files.readAllBytes(repository.indexFile());
        assertThrows(
                java.io.IOException.class,
                () -> new AddService().add(logical, logical.resolve("escape/../file")));
        assertThrows(
                java.io.IOException.class,
                () -> new RestoreService().restore(logical, Path.of("escape/file"), null));
        assertArrayEquals(index, Files.readAllBytes(repository.indexFile()));
        assertEquals("tracked", Files.readString(root.resolve("file")));
        try (var files = Files.list(outside)) {
            assertEquals(0, files.count());
        }
    }
}
