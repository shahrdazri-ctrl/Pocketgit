package com.pocketgit.diff;

public record DiffLine(Type type, String text, boolean terminated) {
    public enum Type {
        CONTEXT,
        ADDED,
        REMOVED
    }
}
