package com.pocketgit.services;

import java.io.IOException;

/** Explicitly identifies a successful ref publication followed by a metadata failure. */
public final class PublishedCommitException extends IOException {
    public PublishedCommitException(String hash, IOException failure) {
        super("commit " + hash + " was published, but metadata completion failed: " + failure.getMessage()
                + ". Inspect the branch and reflog before retrying.", failure);
    }
}
