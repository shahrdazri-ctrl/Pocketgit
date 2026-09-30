package com.pocketgit.services;

import com.pocketgit.model.FileMode;
import com.pocketgit.model.Index;
import com.pocketgit.model.IndexEntry;
import com.pocketgit.model.ObjectType;
import com.pocketgit.model.Tree;
import com.pocketgit.model.TreeEntry;
import com.pocketgit.storage.ObjectCodec;
import com.pocketgit.storage.ObjectStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.TreeMap;

public final class TreeBuilder {
    public static final int MAX_DEPTH = 256;
    public static final int MAX_ENTRIES = 100_000;

    private static final class Node {
        final TreeMap<String, Node> directories = new TreeMap<>();
        final TreeMap<String, IndexEntry> files = new TreeMap<>();
    }

    private final ObjectStore objects;
    private final ObjectCodec codec = new ObjectCodec();

    public TreeBuilder(ObjectStore objects) {
        this.objects = objects;
    }

    public String build(Index index) throws IOException {
        Node root = new Node();
        var verifiedBlobs = new java.util.HashSet<String>();
        int count = 1;
        for (var entry : index.entries()) {
            if (++count > MAX_ENTRIES) throw new IOException("snapshot exceeds tree entry limit");
            String[] parts = entry.path().split("/");
            if (parts.length > MAX_DEPTH)
                throw new IOException("snapshot exceeds maximum path depth of " + MAX_DEPTH);
            if (verifiedBlobs.add(entry.blobHash())) objects.verifyBlob(entry.blobHash());
            Node node = root;
            for (int i = 0; i < parts.length - 1; i++) {
                Node child = node.directories.get(parts[i]);
                if (child == null) {
                    if (++count > MAX_ENTRIES)
                        throw new IOException("snapshot exceeds tree entry limit");
                    child = new Node();
                    node.directories.put(parts[i], child);
                }
                node = child;
            }
            node.files.put(parts[parts.length - 1], entry);
        }
        return write(root);
    }

    private String write(Node node) throws IOException {
        var entries = new ArrayList<TreeEntry>();
        for (var directory : node.directories.entrySet())
            entries.add(
                    new TreeEntry(
                            directory.getKey(),
                            ObjectType.TREE,
                            write(directory.getValue()),
                            FileMode.DIRECTORY));
        for (var file : node.files.entrySet())
            entries.add(
                    new TreeEntry(
                            file.getKey(),
                            ObjectType.BLOB,
                            file.getValue().blobHash(),
                            file.getValue().mode()));
        return objects.write(ObjectType.TREE, codec.encodeTree(new Tree(entries)));
    }
}
