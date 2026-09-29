package com.pocketgit.storage;

import com.pocketgit.model.ObjectType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Hashes the canonical uncompressed type/byte-length header and payload. */
public final class ObjectHasher {
    public byte[] canonicalBytes(ObjectType type, byte[] payload) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        byte[] header = (type.token() + " " + payload.length + "\0").getBytes(StandardCharsets.US_ASCII);
        byte[] canonical = new byte[Math.addExact(header.length, payload.length)];
        System.arraycopy(header, 0, canonical, 0, header.length);
        System.arraycopy(payload, 0, canonical, header.length, payload.length);
        return canonical;
    }

    public String hash(ObjectType type, byte[] payload) { return hashCanonical(canonicalBytes(type, payload)); }

    public String hashCanonical(byte[] canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", impossible);
        }
    }
}
