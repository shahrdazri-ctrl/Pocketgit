package com.pocketgit.validation;

public final class RefNameValidator {
    private RefNameValidator() {}
    public static void validateBranch(String name) {
        if (name == null || name.isBlank() || name.equals("HEAD") || name.startsWith("/") || name.endsWith("/")
                || name.contains("..") || name.contains("@{") || name.startsWith("-")) throw new IllegalArgumentException("invalid branch name");
        for (char c : name.toCharArray()) {
            if (Character.isISOControl(c) || Character.isWhitespace(c) || "~^:?*[\\".indexOf(c) >= 0) throw new IllegalArgumentException("invalid branch name");
        }
        for (String component : name.split("/", -1)) {
            if (component.isEmpty() || component.startsWith(".") || component.endsWith(".") || component.endsWith(".lock")) throw new IllegalArgumentException("invalid branch name");
        }
    }
}
