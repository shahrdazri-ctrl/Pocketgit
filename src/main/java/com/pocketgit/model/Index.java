package com.pocketgit.model;

import com.pocketgit.util.PathUtils;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record Index(int version, List<IndexEntry> entries) {
    public Index {
        if (version != 1)
            throw new IllegalArgumentException("unsupported index version: " + version);
        entries =
                Objects.requireNonNull(entries, "entries").stream()
                        .sorted(Comparator.comparing(IndexEntry::path))
                        .toList();
        PathUtils.validateSnapshotPaths(entries.stream().map(IndexEntry::path).toList());
        var paths = new HashSet<String>();
        for (IndexEntry entry : entries) {
            if (!paths.add(entry.path()))
                throw new IllegalArgumentException("duplicate index path: " + entry.path());
        }
        for (String path : paths) {
            for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
                if (paths.contains(path.substring(0, slash))) {
                    throw new IllegalArgumentException("file/directory conflict in index: " + path);
                }
            }
        }
    }
}
