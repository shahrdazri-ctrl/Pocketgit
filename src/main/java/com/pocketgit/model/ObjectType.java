package com.pocketgit.model;

public enum ObjectType {
    BLOB("blob"),
    TREE("tree"),
    COMMIT("commit");

    private final String token;

    ObjectType(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public static ObjectType fromToken(String token) {
        for (ObjectType type : values()) if (type.token.equals(token)) return type;
        throw new IllegalArgumentException("unknown object type: " + token);
    }
}
