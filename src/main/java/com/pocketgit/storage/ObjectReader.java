package com.pocketgit.storage;

import com.pocketgit.model.ObjectType;
import com.pocketgit.model.StoredObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Streaming validation of the complete zlib stream, canonical header, size, and SHA-256. */
public final class ObjectReader {
    static final int MAX_HEADER_BYTES = 128;

    public record Summary(ObjectType type, int size) {}

    @FunctionalInterface
    private interface PayloadTarget {
        OutputStream open(Summary summary) throws IOException;
    }

    private final ObjectPaths paths;
    private final int maxPayloadBytes;

    ObjectReader(ObjectPaths paths, int maxPayloadBytes) {
        this.paths = paths;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public StoredObject read(String hash) throws IOException {
        return read(hash, null);
    }

    /** Check a required type before retaining any payload bytes. */
    public StoredObject read(String hash, ObjectType expected) throws IOException {
        var payload = new ByteArrayOutputStream[1];
        Summary summary =
                consume(
                        hash,
                        header -> {
                            if (expected != null && header.type() != expected) {
                                throw new CorruptObjectException(
                                        "expected "
                                                + expected.token()
                                                + " object, found "
                                                + header.type().token());
                            }
                            // An untrusted declared size must not trigger a large allocation.
                            payload[0] = new ByteArrayOutputStream(Math.min(header.size(), 8192));
                            return payload[0];
                        });
        return new StoredObject(summary.type(), payload[0].toByteArray());
    }

    public Summary verify(String hash) throws IOException {
        return consume(hash, ignored -> OutputStream.nullOutputStream());
    }

    /** The caller owns the sink. Keep copied bytes private until this method succeeds. */
    public Summary copy(String hash, ObjectType expected, OutputStream output) throws IOException {
        return consume(
                hash,
                header -> {
                    if (header.type() != expected) {
                        throw new IOException("object is not a " + expected.token() + ": " + hash);
                    }
                    return output;
                });
    }

    private Summary consume(String hash, PayloadTarget target) throws IOException {
        ObjectPaths.validateHash(hash);
        InputStream input;
        try {
            paths.checkPrefix(hash);
            var path = paths.pathForHash(hash);
            var attributes =
                    Files.readAttributes(
                            path,
                            java.nio.file.attribute.BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw new CorruptObjectException("object must be a regular file: " + hash);
            }
            input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            paths.checkBase();
            throw new ObjectNotFoundException(hash);
        }
        try (input) {
            return inflate(input, hash, target);
        }
    }

    private Summary inflate(InputStream input, String hash, PayloadTarget target)
            throws IOException {
        var inflater = new Inflater();
        byte[] compressed = new byte[8192];
        byte[] chunk = new byte[8192];
        var header = new ByteArrayOutputStream(MAX_HEADER_BYTES);
        MessageDigest digest = sha256();
        Summary summary = null;
        OutputStream output = null;
        long payloadBytes = 0;
        try {
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    int count = input.read(compressed);
                    if (count < 0)
                        throw new CorruptObjectException("truncated compressed object: " + hash);
                    inflater.setInput(compressed, 0, count);
                }
                int count = inflater.inflate(chunk);
                digest.update(chunk, 0, count);
                int offset = 0;
                while (summary == null && offset < count) {
                    int value = Byte.toUnsignedInt(chunk[offset++]);
                    if (value == 0) {
                        summary = parseHeader(header.toString(StandardCharsets.US_ASCII), hash);
                        output = target.open(summary);
                    } else {
                        if (value > 127 || header.size() >= MAX_HEADER_BYTES - 1) {
                            throw new CorruptObjectException("invalid object header: " + hash);
                        }
                        header.write(value);
                    }
                }
                if (summary != null) {
                    int remaining = count - offset;
                    if (remaining > summary.size() - payloadBytes) {
                        throw new CorruptObjectException("object payload length mismatch: " + hash);
                    }
                    output.write(chunk, offset, remaining);
                    payloadBytes += remaining;
                }
                if (inflater.needsDictionary()) {
                    throw new CorruptObjectException("unsupported compression dictionary: " + hash);
                }
                if (count == 0 && !inflater.finished() && !inflater.needsInput()) {
                    throw new CorruptObjectException("invalid compressed object: " + hash);
                }
            }
            if (inflater.getRemaining() != 0 || input.read() != -1) {
                throw new CorruptObjectException("trailing compressed object data: " + hash);
            }
            if (summary == null)
                throw new CorruptObjectException(
                        "invalid object header: missing NUL delimiter: " + hash);
            if (payloadBytes != summary.size()) {
                throw new CorruptObjectException("object payload length mismatch: " + hash);
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(hash)) {
                throw new CorruptObjectException("object hash mismatch: " + hash);
            }
            return summary;
        } catch (DataFormatException invalid) {
            throw new CorruptObjectException("invalid compressed object: " + hash, invalid);
        } finally {
            inflater.end();
        }
    }

    private Summary parseHeader(String header, String hash) throws CorruptObjectException {
        String[] fields = header.split(" ", -1);
        if (fields.length != 2 || !fields[1].matches("0|[1-9][0-9]*")) {
            throw new CorruptObjectException("invalid object header: " + hash);
        }
        ObjectType type;
        try {
            type = ObjectType.fromToken(fields[0]);
        } catch (IllegalArgumentException invalid) {
            throw new CorruptObjectException("unknown object type: " + fields[0]);
        }
        long size;
        try {
            size = Long.parseLong(fields[1]);
        } catch (NumberFormatException invalid) {
            throw new CorruptObjectException("invalid object length: " + hash);
        }
        if (size > maxPayloadBytes) {
            throw new CorruptObjectException("object exceeds configured payload limit: " + hash);
        }
        return new Summary(type, (int) size);
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", impossible);
        }
    }
}
