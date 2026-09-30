package com.pocketgit.model;

import com.pocketgit.util.HashUtils;
import com.pocketgit.util.PathUtils;
import java.util.Objects;

public record IndexEntry(String path, String blobHash, FileMode mode) {
    public IndexEntry {
        PathUtils.validateIndexPath(path);
        HashUtils.validateSha256(blobHash);
        Objects.requireNonNull(mode, "mode");
        if (mode == FileMode.DIRECTORY) throw new IllegalArgumentException("index entries must reference files");
    }
}
