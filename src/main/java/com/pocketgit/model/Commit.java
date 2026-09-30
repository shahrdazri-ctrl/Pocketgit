package com.pocketgit.model;

import com.pocketgit.util.HashUtils;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record Commit(String treeHash, List<String> parentHashes, String authorName,
                     String authorEmail, Instant timestamp, String message) {
    public Commit {
        HashUtils.validateSha256(treeHash);
        parentHashes = List.copyOf(parentHashes);
        var parents = new HashSet<String>();
        for (String hash : parentHashes) {
            HashUtils.validateSha256(hash);
            if (!parents.add(hash)) throw new IllegalArgumentException("duplicate commit parent");
        }
        var author = new AuthorIdentity(authorName, authorEmail);
        authorName = author.name();
        authorEmail = author.email();
        Objects.requireNonNull(timestamp, "timestamp");
        if (message == null || message.isBlank() || message.indexOf('\0') >= 0) throw new IllegalArgumentException("commit message must be nonblank and contain no NUL");
    }
}
