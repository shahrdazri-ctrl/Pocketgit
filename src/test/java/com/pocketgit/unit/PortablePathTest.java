package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketgit.model.FileMode;
import com.pocketgit.model.Index;
import com.pocketgit.model.IndexEntry;
import com.pocketgit.model.ObjectType;
import com.pocketgit.model.Tree;
import com.pocketgit.model.TreeEntry;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.util.PathUtils;
import com.pocketgit.validation.RefNameValidator;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PortablePathTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(
            strings = {
                ".pocketgit./HEAD",
                ".PocketGit /HEAD",
                "NUL",
                "CON.txt",
                "aux/data",
                "COM1.log",
                "LPT9",
                "com².txt",
                "file.",
                "file ",
                "a?b",
                "a*b",
                "a|b",
                "a\"b",
                "<file>"
            })
    void rejectsFilesystemAliasesAndDeviceNamesInSnapshots(String path) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexEntry(path, "a".repeat(64), FileMode.REGULAR_FILE));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "feature/CON",
                "NUL.txt",
                "feature.",
                "feature ",
                "AUX",
                "LPT1",
                "feature/<unsafe>"
            })
    void branchesUseTheSamePortableComponentRules(String branch) {
        assertThrows(IllegalArgumentException.class, () -> RefNameValidator.validateBranch(branch));
    }

    @Test
    void workingPathCannotAddressMetadataThroughWindowsAlias() throws Exception {
        Repository repository = new RepositoryInitializer().initialize(root).repository();
        assertThrows(
                java.io.IOException.class,
                () ->
                        PathUtils.safeWorkingPath(
                                repository.root(), root.resolve(".pocketgit./HEAD")));
        assertThrows(
                java.io.IOException.class,
                () -> PathUtils.safeWorkingPath(repository.root(), root.resolve("NUL")));
    }

    @Test
    void normalUnicodeSpacesAndNonDeviceNamesRemainSupported() {
        for (String path :
                new String[] {
                    "文档/hello world.txt",
                    "CONTRIBUTING.md",
                    "config",
                    "com10",
                    "null.txt",
                    "naïve/café.java"
                }) {
            assertDoesNotThrow(() -> PathUtils.validateIndexPath(path));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "README.md|readme.md",
                "src/A.java|SRC/B.java",
                "café.txt|café.txt",
                "A|a/child",
                "Σ.txt|ς.txt",
                "straße.txt|STRAẞE.txt"
            })
    void snapshotCannotContainFilesystemAliases(String pair) {
        var entries =
                java.util.Arrays.stream(pair.split("\\|"))
                        .map(path -> new IndexEntry(path, "a".repeat(64), FileMode.REGULAR_FILE))
                        .toList();
        assertThrows(IllegalArgumentException.class, () -> new Index(1, entries));
    }

    @Test
    void treeCannotContainCaseAliasesOfSiblings() {
        var first = new TreeEntry("README", ObjectType.BLOB, "a".repeat(64), FileMode.REGULAR_FILE);
        var second =
                new TreeEntry("readme", ObjectType.BLOB, "b".repeat(64), FileMode.REGULAR_FILE);
        assertThrows(IllegalArgumentException.class, () -> new Tree(List.of(first, second)));
    }
}
