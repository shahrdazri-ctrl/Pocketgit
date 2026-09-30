package com.pocketgit.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.pocketgit.util.PathUtils;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

@JsonPropertyOrder({"entries"})
public record Tree(List<TreeEntry> entries) {
    public Tree {
        entries =
                Objects.requireNonNull(entries, "entries").stream()
                        .sorted(Comparator.comparing(TreeEntry::name))
                        .toList();
        PathUtils.validateSnapshotPaths(entries.stream().map(TreeEntry::name).toList());
        var names = new HashSet<String>();
        for (var entry : entries)
            if (!names.add(entry.name()))
                throw new IllegalArgumentException("duplicate tree name: " + entry.name());
    }
}
