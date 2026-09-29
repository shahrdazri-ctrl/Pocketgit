package com.pocketgit.repository;

import java.io.IOException;

/** Metadata exists but cannot safely be treated as a repository. */
public final class InvalidRepositoryException extends IOException {
    public InvalidRepositoryException(String message) { super(message); }
}
