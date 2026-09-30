package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.services.AddService;
import com.pocketgit.services.DiffService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class DiffServiceResourceTest {
    @TempDir Path root;

    @Test
    void individuallyValidComparisonsCannotAccumulateUnboundedText() throws Exception {
        new RepositoryInitializer().initialize(root);
        String original = "a".repeat(6 * 1024 * 1024);
        for (int i = 0; i < 3; i++) Files.writeString(root.resolve("file" + i), original);
        new AddService().add(root, Path.of("."));
        byte[] index = Files.readAllBytes(root.resolve(".pocketgit/index"));
        String changed = "b".repeat(6 * 1024 * 1024);
        for (int i = 0; i < 3; i++) Files.writeString(root.resolve("file" + i), changed);
        var failure = assertThrows(IOException.class, () -> new DiffService().diff(root, false));
        assertTrue(failure.getMessage().contains("cumulative text limit"));
        assertArrayEquals(index, Files.readAllBytes(root.resolve(".pocketgit/index")));
    }

    @Test
    void individuallyValidDeletionsCannotAccumulateUnboundedOutputLines() throws Exception {
        new RepositoryInitializer().initialize(root);
        for (int i = 0; i < 3; i++) {
            Files.writeString(root.resolve("file" + i), "line\n".repeat(90_000));
        }
        new AddService().add(root, Path.of("."));
        byte[] index = Files.readAllBytes(root.resolve(".pocketgit/index"));
        for (int i = 0; i < 3; i++) Files.delete(root.resolve("file" + i));
        var failure = assertThrows(IOException.class, () -> new DiffService().diff(root, false));
        assertTrue(failure.getMessage().contains("cumulative output lines"));
        assertArrayEquals(index, Files.readAllBytes(root.resolve(".pocketgit/index")));
    }
}
