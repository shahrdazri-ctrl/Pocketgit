package com.pocketgit.services;

import com.pocketgit.model.Index;
import com.pocketgit.model.IndexEntry;
import com.pocketgit.model.ObjectType;
import com.pocketgit.storage.CorruptObjectException;
import com.pocketgit.storage.ObjectCodec;
import com.pocketgit.storage.ObjectStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Flattens verified Tree graphs into bounded, portable snapshots for repository services. */
public final class TreeReader {
    private final ObjectStore objects;
    private final ObjectCodec codec = new ObjectCodec();

    public TreeReader(ObjectStore objects) {
        this.objects = objects;
    }

    public Index readSnapshot(String rootHash) throws IOException {
        var entries = new ArrayList<IndexEntry>();
        read(rootHash, "", 0, new HashSet<>(), new HashSet<>(), entries, new int[] {1});
        try {
            return new Index(1, entries);
        } catch (RuntimeException invalid) {
            throw new CorruptObjectException("invalid snapshot: " + invalid.getMessage(), invalid);
        }
    }

    private void read(
            String hash,
            String prefix,
            int depth,
            Set<String> active,
            Set<String> verifiedBlobs,
            List<IndexEntry> result,
            int[] count)
            throws IOException {
        if (depth >= TreeBuilder.MAX_DEPTH || !active.add(hash))
            throw new CorruptObjectException("cyclic or excessively deep tree graph");
        try {
            for (var entry : codec.decodeTree(objects.readTree(hash)).entries()) {
                if (++count[0] > TreeBuilder.MAX_ENTRIES)
                    throw new CorruptObjectException("snapshot exceeds tree entry limit");
                String path = prefix + entry.name();
                if (entry.type() == ObjectType.TREE)
                    read(
                            entry.objectHash(),
                            path + "/",
                            depth + 1,
                            active,
                            verifiedBlobs,
                            result,
                            count);
                else {
                    if (verifiedBlobs.add(entry.objectHash()))
                        objects.verifyBlob(entry.objectHash());
                    result.add(new IndexEntry(path, entry.objectHash(), entry.mode()));
                }
            }
        } finally {
            active.remove(hash);
        }
    }
}
