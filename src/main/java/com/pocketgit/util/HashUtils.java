package com.pocketgit.util;

public final class HashUtils {
    private HashUtils() {}

    public static void validateSha256(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "invalid object ID: expected 64 lowercase hexadecimal characters");
        }
    }
}
