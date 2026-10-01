package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketgit.refs.BranchService;
import com.pocketgit.repository.*;
import com.pocketgit.services.*;
import com.pocketgit.storage.ConfigStore;
import com.pocketgit.validation.IntegrityChecker;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotReliabilityTest {
    @TempDir Path temp;

    private Repository initialize(Path root) throws Exception {
        Files.createDirectories(root);
        var repo = new RepositoryInitializer().initialize(root).repository();
        new ConfigStore(repo).set("user.name", "Reviewer");
        new ConfigStore(repo).set("user.email", "reviewer@example.com");
        return repo;
    }

    private void commit(Path root) throws Exception {
        new AddService().add(root, Path.of("."));
        new CommitService().commit(root, "snapshot", false);
    }

    @Test
    void seededRandomSnapshotsRoundTripThroughCheckoutAndRestore() throws Exception {
        var random = new Random(9708);
        for (int run = 0; run < 6; run++) {
            Path root = temp.resolve("run-" + run);
            initialize(root);
            var original = new TreeMap<String, byte[]>();
            for (int i = 0; i < 25; i++) {
                String path = "dir" + (i % 5) + "/file" + i;
                byte[] bytes = new byte[random.nextInt(100)];
                random.nextBytes(bytes);
                Path file = root.resolve(path);
                Files.createDirectories(file.getParent());
                Files.write(file, bytes);
                original.put(path, bytes);
            }
            commit(root);
            new BranchService().create(root, "original");
            for (var item : original.entrySet())
                Files.write(root.resolve(item.getKey()), new byte[] {1, 2, 3});
            commit(root);
            new CheckoutService().checkout(root, "original");
            for (var item : original.entrySet())
                assertArrayEquals(item.getValue(), Files.readAllBytes(root.resolve(item.getKey())));
            for (var item : original.entrySet()) {
                Files.write(root.resolve(item.getKey()), new byte[] {4});
                new RestoreService().restore(root, Path.of(item.getKey()), null);
                assertArrayEquals(item.getValue(), Files.readAllBytes(root.resolve(item.getKey())));
            }
            assertTrue(new IntegrityChecker().verify(root).valid());
        }
    }

    @Test
    void thousandFileRepositoryRemainsUsableAndRecordsBenchmark() throws Exception {
        Path root = temp.resolve("large");
        initialize(root);
        for (int i = 0; i < 1000; i++) {
            Path file = root.resolve("dir" + (i % 20) + "/file" + i);
            Files.createDirectories(file.getParent());
            Files.write(
                    file,
                    i % 2 == 0
                            ? ("text " + i + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)
                            : new byte[] {0, (byte) i, (byte) (i >> 8), 7});
        }
        long start = System.nanoTime();
        new AddService().add(root, Path.of("."));
        long added = System.nanoTime();
        new CommitService().commit(root, "large", false);
        long committed = System.nanoTime();
        assertTrue(new StatusService().status(root).isClean());
        long status = System.nanoTime();
        var report = new IntegrityChecker().verify(root);
        assertTrue(report.valid(), report.errors().toString());
        System.out.printf(
                java.util.Locale.ROOT,
                "BENCHMARK 1000 files: add=%.3fs commit=%.3fs status=%.3fs verify=%.3fs%n",
                (added - start) / 1e9,
                (committed - added) / 1e9,
                (status - committed) / 1e9,
                (System.nanoTime() - status) / 1e9);
    }
}
