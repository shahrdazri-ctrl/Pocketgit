package com.pocketgit.model;

import java.util.Arrays;
import java.util.Objects;

/** Raw file bytes, never implicitly decoded as text. */
public record Blob(byte[] content) {
    public Blob { content = Objects.requireNonNull(content, "content").clone(); }
    @Override public byte[] content() { return content.clone(); }
    @Override public boolean equals(Object other) {
        return other instanceof Blob blob && Arrays.equals(content, blob.content);
    }
    @Override public int hashCode() { return Arrays.hashCode(content); }
}
