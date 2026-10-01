package com.pocketgit.storage;

import java.io.IOException;

public final class ObjectNotFoundException extends IOException {
    public ObjectNotFoundException(String hash) {
        super("object not found: " + hash);
    }
}
