package com.pocketgit.model;

import java.util.Arrays;
import java.util.Objects;

/** Verified typed payload with defensive copies at the API boundary. */
public record StoredObject(ObjectType type, byte[] payload) {
    public StoredObject {
        Objects.requireNonNull(type, "type");
        payload = Objects.requireNonNull(payload, "payload").clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    public int size() {
        return payload.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StoredObject object
                && type == object.type
                && Arrays.equals(payload, object.payload);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + Arrays.hashCode(payload);
    }
}
