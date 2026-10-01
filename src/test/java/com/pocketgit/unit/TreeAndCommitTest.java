package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketgit.model.*;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.services.TreeBuilder;
import com.pocketgit.services.TreeReader;
import com.pocketgit.storage.ObjectCodec;
import com.pocketgit.storage.ObjectStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TreeAndCommitTest {
    @TempDir Path root;
    private ObjectStore objects;
    private final ObjectCodec codec = new ObjectCodec();
    private static final String HASH = "a".repeat(64);

    @BeforeEach
    void initialize() throws Exception {
        Repository repo = new RepositoryInitializer().initialize(root).repository();
        objects = new ObjectStore(repo);
    }

    private String blob(String value) throws IOException {
        return objects.writeBlob(new Blob(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String tree(Tree value) throws IOException {
        return objects.write(ObjectType.TREE, codec.encodeTree(value));
    }

    private Commit commit() {
        return new Commit(
                HASH,
                List.of(),
                "Jane 日本語",
                "jane@example.com",
                Instant.parse("2026-01-02T03:04:05Z"),
                "subject\n\nbody");
    }

    @Test
    void buildsNestedDeterministicSnapshotWithModesAndExactBlobs() throws Exception {
        var source =
                new ArrayList<>(
                        List.of(
                                new IndexEntry("z", blob("z"), FileMode.REGULAR_FILE),
                                new IndexEntry(
                                        "src/Main.java", blob("main"), FileMode.REGULAR_FILE),
                                new IndexEntry(
                                        "src/util/run", blob("run"), FileMode.EXECUTABLE_FILE),
                                new IndexEntry("empty", blob(""), FileMode.REGULAR_FILE)));
        var index = new Index(1, source);
        String first = new TreeBuilder(objects).build(index);
        java.util.Collections.reverse(source);
        assertEquals(first, new TreeBuilder(objects).build(new Index(1, source)));
        assertEquals(index, new TreeReader(objects).readSnapshot(first));
        Tree rootTree = codec.decodeTree(objects.read(first));
        assertEquals(
                List.of("empty", "src", "z"),
                rootTree.entries().stream().map(TreeEntry::name).toList());
        var directory = rootTree.entries().get(1);
        assertEquals(ObjectType.TREE, directory.type());
        assertEquals(FileMode.DIRECTORY, directory.mode());
        assertEquals(
                List.of("Main.java", "util"),
                codec.decodeTree(objects.read(directory.objectHash())).entries().stream()
                        .map(TreeEntry::name)
                        .toList());
    }

    @Test
    void emptyTreeHasSpecifiedCanonicalBytesAndStableId() throws Exception {
        var empty = new Index(1, List.of());
        String hash = new TreeBuilder(objects).build(empty);
        assertArrayEquals(
                "{\"entries\":[]}".getBytes(StandardCharsets.UTF_8), objects.read(hash).payload());
        assertEquals(empty, new TreeReader(objects).readSnapshot(hash));
    }

    @Test
    void canonicalPropertyOrderAndEncodingAreExplicit() throws Exception {
        var tree =
                new Tree(
                        List.of(
                                new TreeEntry(
                                        "file", ObjectType.BLOB, HASH, FileMode.REGULAR_FILE)));
        assertEquals(
                "{\"entries\":[{\"name\":\"file\",\"type\":\"BLOB\",\"objectHash\":\""
                        + HASH
                        + "\",\"mode\":\"REGULAR_FILE\"}]}",
                new String(codec.encodeTree(tree), StandardCharsets.UTF_8));
        assertEquals(
                "{\"treeHash\":\""
                        + HASH
                        + "\",\"parentHashes\":[],\"authorName\":\"Jane"
                        + " 日本語\",\"authorEmail\":\"jane@example.com\",\"timestamp\":\"2026-01-02T03:04:05Z\",\"message\":\"subject\\n"
                        + "\\n"
                        + "body\"}",
                new String(codec.encodeCommit(commit()), StandardCharsets.UTF_8));
    }

    @Test
    void commitRoundTripPreservesAuthorParentsTimestampAndMultilineMessage() throws Exception {
        Commit value = commit();
        String id = objects.write(ObjectType.COMMIT, codec.encodeCommit(value));
        assertEquals(value, codec.decodeCommit(objects.read(id)));
        assertTrue(codec.pretty(objects.read(id)).contains("\"parentHashes\" : [ ]"));
        assertEquals(id, objects.write(ObjectType.COMMIT, codec.encodeCommit(value)));
        var parents = new ArrayList<>(List.of(HASH, "b".repeat(64)));
        Commit merge =
                new Commit(HASH, parents, "Jane", "jane@example.com", value.timestamp(), "merge");
        parents.clear();
        assertEquals(2, merge.parentHashes().size());
        assertThrows(UnsupportedOperationException.class, () -> merge.parentHashes().clear());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape", "a/b", ".pocketgit", "a\\b", "", ".", "a:b", "a\n"})
    void rejectsInvalidNames(String name) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TreeEntry(name, ObjectType.BLOB, HASH, FileMode.REGULAR_FILE));
    }

    @Test
    void rejectsDuplicateNamesMismatchedModesAndInvalidCommitMetadata() {
        var entry = new TreeEntry("a", ObjectType.BLOB, HASH, FileMode.REGULAR_FILE);
        assertThrows(IllegalArgumentException.class, () -> new Tree(List.of(entry, entry)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TreeEntry("a", ObjectType.TREE, HASH, FileMode.REGULAR_FILE));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TreeEntry("a", ObjectType.BLOB, HASH, FileMode.DIRECTORY));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TreeEntry("a", ObjectType.COMMIT, HASH, FileMode.REGULAR_FILE));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexEntry("a", HASH, FileMode.DIRECTORY));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Commit(
                                HASH,
                                List.of(HASH, HASH),
                                "Jane",
                                "jane@example.com",
                                Instant.EPOCH,
                                "message"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Commit(HASH, List.of(), "Jane", "invalid", Instant.EPOCH, "message"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Commit(HASH, List.of(), "Jane", "jane@example.com", Instant.EPOCH, " "));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "{\"entries\":[],\"extra\":1}",
                "{\"entries\":[],\"entries\":[]}",
                "{\"entries\":[]} {}",
                "{ \"entries\": [] }",
                "{}"
            })
    void rejectsInvalidOrNoncanonicalTreePayload(String json) throws Exception {
        String id = objects.write(ObjectType.TREE, json.getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> codec.decodeTree(objects.read(id)));
    }

    @Test
    void rejectsNoncanonicalCommitAndWrongObjectTypes() throws Exception {
        String json = new String(codec.encodeCommit(commit()), StandardCharsets.UTF_8);
        for (String altered :
                List.of(
                        " " + json,
                        json.replace("2026-01-02T03:04:05Z", "bad"),
                        json.replace("\"parentHashes\":[]", "\"parentHashes\":null"),
                        json.replace("\"message\":", "\"extra\":1,\"message\":"))) {
            String id = objects.write(ObjectType.COMMIT, altered.getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> codec.decodeCommit(objects.read(id)));
        }
        String id = blob("blob");
        assertThrows(IOException.class, () -> codec.decodeTree(objects.read(id)));
        assertThrows(IOException.class, () -> codec.decodeCommit(objects.read(id)));
    }

    @Test
    void rejectsMissingOrNonblobIndexedObjectsBeforeWritingTrees() throws Exception {
        assertThrows(
                IOException.class,
                () ->
                        new TreeBuilder(objects)
                                .build(
                                        new Index(
                                                1,
                                                List.of(
                                                        new IndexEntry(
                                                                "a",
                                                                HASH,
                                                                FileMode.REGULAR_FILE)))));
        String wrong = tree(new Tree(List.of()));
        assertThrows(
                IOException.class,
                () ->
                        new TreeBuilder(objects)
                                .build(
                                        new Index(
                                                1,
                                                List.of(
                                                        new IndexEntry(
                                                                "a",
                                                                wrong,
                                                                FileMode.REGULAR_FILE)))));
    }

    @Test
    void readerRejectsMissingAndMistypedDescendantsButAllowsSharedTrees() throws Exception {
        String leaf =
                tree(
                        new Tree(
                                List.of(
                                        new TreeEntry(
                                                "file",
                                                ObjectType.BLOB,
                                                blob("hello"),
                                                FileMode.EXECUTABLE_FILE))));
        String shared =
                tree(
                        new Tree(
                                List.of(
                                        new TreeEntry(
                                                "a", ObjectType.TREE, leaf, FileMode.DIRECTORY),
                                        new TreeEntry(
                                                "b", ObjectType.TREE, leaf, FileMode.DIRECTORY))));
        assertEquals(
                List.of("a/file", "b/file"),
                new TreeReader(objects)
                        .readSnapshot(shared).entries().stream().map(IndexEntry::path).toList());
        String missing =
                tree(
                        new Tree(
                                List.of(
                                        new TreeEntry(
                                                "missing",
                                                ObjectType.BLOB,
                                                HASH,
                                                FileMode.REGULAR_FILE))));
        assertThrows(IOException.class, () -> new TreeReader(objects).readSnapshot(missing));
        String wrong =
                tree(
                        new Tree(
                                List.of(
                                        new TreeEntry(
                                                "wrong",
                                                ObjectType.BLOB,
                                                leaf,
                                                FileMode.REGULAR_FILE))));
        assertThrows(IOException.class, () -> new TreeReader(objects).readSnapshot(wrong));
    }

    @Test
    void builderAndReaderBoundDeepGraphs() throws Exception {
        String blob = blob("hello");
        String deepPath = "dir/".repeat(TreeBuilder.MAX_DEPTH) + "file";
        assertThrows(
                IOException.class,
                () ->
                        new TreeBuilder(objects)
                                .build(
                                        new Index(
                                                1,
                                                List.of(
                                                        new IndexEntry(
                                                                deepPath,
                                                                blob,
                                                                FileMode.REGULAR_FILE)))));
        String current = tree(new Tree(List.of()));
        for (int i = 0; i < TreeBuilder.MAX_DEPTH; i++)
            current =
                    tree(
                            new Tree(
                                    List.of(
                                            new TreeEntry(
                                                    "dir",
                                                    ObjectType.TREE,
                                                    current,
                                                    FileMode.DIRECTORY))));
        String tooDeep = current;
        assertThrows(IOException.class, () -> new TreeReader(objects).readSnapshot(tooDeep));
    }

    @Test
    void readerBoundsRepeatedDagExpansionIncludingEmptyDirectories() throws Exception {
        String current = tree(new Tree(List.of()));
        for (int i = 0; i < 5; i++) {
            var entries = new ArrayList<TreeEntry>();
            for (int n = 0; n < 10; n++)
                entries.add(new TreeEntry("d" + n, ObjectType.TREE, current, FileMode.DIRECTORY));
            current = tree(new Tree(entries));
        }
        String wide = current;
        assertThrows(IOException.class, () -> new TreeReader(objects).readSnapshot(wide));
    }
}
