package com.pocketgit.model;

public record AuthorIdentity(String name, String email) {
    public AuthorIdentity {
        name = validate(name, "author name");
        email = validate(email, "author email");
        if (!email.contains("@")
                || email.startsWith("@")
                || email.endsWith("@")
                || email.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("invalid author email");
    }

    private static String validate(String value, String label) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(label + " is not configured");
        value = value.strip();
        if (value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("control character in " + label);
        return value;
    }
}
