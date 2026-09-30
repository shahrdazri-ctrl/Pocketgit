package com.pocketgit.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.pocketgit.util.HashUtils;
import com.pocketgit.util.PathUtils;
import java.util.Objects;

@JsonPropertyOrder({"name", "type", "objectHash", "mode"})
public record TreeEntry(String name, ObjectType type, String objectHash, FileMode mode) {
    public TreeEntry {
        PathUtils.validateIndexPath(name);
        if (name.contains("/")) throw new IllegalArgumentException("tree entry must be a single name");
        HashUtils.validateSha256(objectHash);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mode, "mode");
        if (type == ObjectType.COMMIT || (type == ObjectType.TREE) != (mode == FileMode.DIRECTORY)) {
            throw new IllegalArgumentException("tree entry type and mode disagree");
        }
    }
}
