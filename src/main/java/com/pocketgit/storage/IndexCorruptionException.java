package com.pocketgit.storage;

import java.io.IOException;

public final class IndexCorruptionException extends IOException {
    public IndexCorruptionException(String message) {
        super(message);
    }

    public IndexCorruptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
