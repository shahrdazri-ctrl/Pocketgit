package com.pocketgit.storage;

import com.pocketgit.model.ObjectType;
import com.pocketgit.model.StoredObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Validates compression, canonical header, payload length, and SHA-256 on every read. */
public final class ObjectReader {
    static final int MAX_HEADER_BYTES = 128;
    private final ObjectPaths paths;
    private final int maxPayloadBytes;
    private final ObjectHasher hasher = new ObjectHasher();

    ObjectReader(ObjectPaths paths, int maxPayloadBytes) {
        this.paths = paths;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public StoredObject read(String hash) throws IOException {
        ObjectPaths.validateHash(hash);
        byte[] canonical;
        // Missing object prefixes and leaves map to the same purposeful error.
        try {
            paths.checkPrefix(hash);
            var path = paths.pathForHash(hash);
            var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw new CorruptObjectException("object must be a regular file: " + hash);
            }
            try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                canonical = inflate(input, hash);
            }
        } catch (NoSuchFileException missing) {
            // Do not disguise missing repository metadata as a missing object.
            paths.checkBase();
            throw new ObjectNotFoundException(hash);
        }
        StoredObject object = parse(canonical, hash);
        if (!hasher.hashCanonical(canonical).equals(hash)) {
            throw new CorruptObjectException("object hash mismatch: " + hash);
        }
        return object;
    }

    private byte[] inflate(InputStream input, String hash) throws IOException {
        Inflater inflater = new Inflater();
        byte[] compressed = new byte[8192];
        byte[] chunk = new byte[8192];
        var output = new ByteArrayOutputStream();
        try {
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    int count = input.read(compressed);
                    if (count < 0) throw new CorruptObjectException("truncated compressed object: " + hash);
                    inflater.setInput(compressed, 0, count);
                }
                int count = inflater.inflate(chunk);
                if ((long) output.size() + count > (long) maxPayloadBytes + MAX_HEADER_BYTES) {
                    throw new CorruptObjectException("object exceeds configured payload limit: " + hash);
                }
                output.write(chunk, 0, count);
                if (inflater.needsDictionary()) throw new CorruptObjectException("unsupported compression dictionary: " + hash);
                if (count == 0 && !inflater.finished() && !inflater.needsInput()) {
                    throw new CorruptObjectException("invalid compressed object: " + hash);
                }
            }
            if (inflater.getRemaining() != 0 || input.read() != -1) {
                throw new CorruptObjectException("trailing compressed object data: " + hash);
            }
            return output.toByteArray();
        } catch (DataFormatException invalid) {
            throw new CorruptObjectException("invalid compressed object: " + hash, invalid);
        } finally { inflater.end(); }
    }

    private StoredObject parse(byte[] canonical, String hash) throws CorruptObjectException {
        int delimiter = -1;
        for (int i = 0; i < Math.min(canonical.length, MAX_HEADER_BYTES); i++) {
            if (canonical[i] == 0) { delimiter = i; break; }
            if (canonical[i] < 0) throw new CorruptObjectException("invalid object header: " + hash);
        }
        if (delimiter < 0) throw new CorruptObjectException("invalid object header: missing NUL delimiter: " + hash);
        String header = new String(canonical, 0, delimiter, StandardCharsets.US_ASCII);
        String[] fields = header.split(" ", -1);
        if (fields.length != 2 || !fields[1].matches("0|[1-9][0-9]*")) {
            throw new CorruptObjectException("invalid object header: " + hash);
        }
        ObjectType type;
        try { type = ObjectType.fromToken(fields[0]); }
        catch (IllegalArgumentException invalid) {
            throw new CorruptObjectException("unknown object type: " + fields[0]);
        }
        long size;
        try { size = Long.parseLong(fields[1]); }
        catch (NumberFormatException invalid) { throw new CorruptObjectException("invalid object length: " + hash); }
        if (size > maxPayloadBytes) throw new CorruptObjectException("object exceeds configured payload limit: " + hash);
        if (size != canonical.length - delimiter - 1) {
            throw new CorruptObjectException("object payload length mismatch: " + hash);
        }
        return new StoredObject(type, Arrays.copyOfRange(canonical, delimiter + 1, canonical.length));
    }
}
