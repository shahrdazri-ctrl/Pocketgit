package com.pocketgit.services;

import java.io.IOException;
import java.util.List;

public final class CheckoutConflictException extends IOException {
    public CheckoutConflictException(List<String> paths) {
        super("local changes or untracked paths would be overwritten by checkout:\n    "
                + String.join("\n    ", paths) + "\nCommit your changes or move conflicting paths before switching branches.");
    }
}
