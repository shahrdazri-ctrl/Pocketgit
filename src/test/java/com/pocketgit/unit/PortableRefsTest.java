package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.pocketgit.model.Commit;
import com.pocketgit.model.ObjectType;
import com.pocketgit.model.Tree;
import com.pocketgit.refs.BranchService;
import com.pocketgit.refs.RefStore;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.services.CheckoutService;
import com.pocketgit.storage.ObjectCodec;
import com.pocketgit.storage.ObjectStore;
import com.pocketgit.validation.IntegrityChecker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

class PortableRefsTest {
    @TempDir Path root;
    private Repository repository;
    private final BranchService branches = new BranchService();
    private String hash;

    @BeforeEach
    void initialize() throws Exception {
        repository = new RepositoryInitializer().initialize(root).repository();
        var objects = new ObjectStore(repository);
        var codec = new ObjectCodec();
        String tree = objects.write(ObjectType.TREE, codec.encodeTree(new Tree(List.of())));
        hash =
                objects.write(
                        ObjectType.COMMIT,
                        codec.encodeCommit(
                                new Commit(
                                        tree,
                                        List.of(),
                                        "Reviewer",
                                        "reviewer@example.com",
                                        Instant.EPOCH,
                                        "initial")));
        new RefStore(repository).updateRef("refs/heads/main", hash);
    }

    private Map<String, String> state() throws Exception {
        var result = new TreeMap<String, String>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList())
                result.put(
                        root.relativize(path).toString(),
                        Files.isDirectory(path)
                                ? "directory"
                                : java.util.HexFormat.of().formatHex(Files.readAllBytes(path)));
        }
        return result;
    }

    @ParameterizedTest
    @CsvSource({"Feature, feature", "Team/one, team/two", "σ, ς", "ẞ, ß", "café, café"})
    void collidingNamesAreRejectedBeforeAnyMetadataChange(String existing, String requested)
            throws Exception {
        branches.create(root, existing);
        var before = state();
        assertThrows(IOException.class, () -> branches.create(root, requested));
        assertEquals(before, state());
        assertEquals(2, branches.list(root).names().size());
        assertTrue(new IntegrityChecker().verify(root).valid());
    }

    @Test
    void emptyDirectoryAliasesAreAlsoRejected() throws Exception {
        Files.createDirectory(repository.headsDirectory().resolve("Team"));
        var before = state();
        assertThrows(IOException.class, () -> branches.create(root, "team/new"));
        assertEquals(before, state());
    }

    @Test
    void manuallyInjectedAliasesAreReportedWithoutRepair() throws Exception {
        branches.create(root, "Feature");
        Path alias = repository.headsDirectory().resolve("feature");
        assumeTrue(!Files.exists(alias), "case-sensitive filesystem required to inject aliases");
        Files.writeString(alias, hash + "\n");
        var before = state();
        assertThrows(IOException.class, () -> branches.list(root));
        var report = new IntegrityChecker().verify(root);
        assertFalse(report.valid());
        assertTrue(
                report.errors().stream()
                        .anyMatch(error -> error.contains("filesystem path collision")));
        assertEquals(before, state());
    }

    @Test
    void caseAliasesCannotReadUpdateOrCheckoutAnExistingBranch() throws Exception {
        branches.create(root, "Feature");
        var before = state();
        var refs = new RefStore(repository);
        assertThrows(IOException.class, () -> refs.readBranch("FEATURE"));
        assertThrows(IOException.class, () -> refs.updateRef("refs/heads/FEATURE", hash));
        assertThrows(IOException.class, () -> new CheckoutService().checkout(root, "FEATURE"));
        assertEquals(before, state());
        assertEquals(hash, refs.readBranch("Feature"));
    }

    @Test
    void unicodeBranchListingMarksTheCurrentBranchOnNormalizingFilesystems() throws Exception {
        branches.create(root, "café");
        new CheckoutService().checkout(root, "café");
        var listing = branches.list(root);
        assertTrue(listing.names().contains(listing.current()));
        assertEquals(
                "café",
                java.text.Normalizer.normalize(listing.current(), java.text.Normalizer.Form.NFC));
    }

    @Test
    void unicodeParentSpellingsCannotCreateSeparateAliasDirectories() throws Exception {
        branches.create(root, "café/one");
        Path aliasParent = repository.headsDirectory().resolve("café");
        if (Files.exists(aliasParent)) {
            branches.create(root, "café/two");
            branches.create(root, "café/three");
            assertEquals(4, branches.list(root).names().size());
        } else {
            var before = state();
            assertThrows(IOException.class, () -> branches.create(root, "café/two"));
            assertEquals(before, state());
        }
        assertTrue(new IntegrityChecker().verify(root).valid());
    }
}
