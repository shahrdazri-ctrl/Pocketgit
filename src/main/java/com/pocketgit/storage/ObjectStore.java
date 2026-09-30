package com.pocketgit.storage;

import com.pocketgit.model.Blob;
import com.pocketgit.model.ObjectType;
import com.pocketgit.model.StoredObject;
import com.pocketgit.repository.Repository;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;

/** Public object database facade for future staging, trees, and commits. */
public final class ObjectStore {
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;
    private final Repository repository;
    private final ObjectPaths paths;
    private final ObjectReader reader;
    private final ObjectWriter writer;

    public ObjectStore(Repository repository) throws IOException {
        this(repository, DEFAULT_MAX_PAYLOAD_BYTES);
    }

    public ObjectStore(Repository repository, int maxPayloadBytes) throws IOException {
        if (maxPayloadBytes < 0
                || maxPayloadBytes > Integer.MAX_VALUE - ObjectReader.MAX_HEADER_BYTES) {
            throw new IllegalArgumentException("invalid object payload limit");
        }
        this.repository = repository;
        paths = new ObjectPaths(repository);
        reader = new ObjectReader(paths, maxPayloadBytes);
        writer = new ObjectWriter(paths, reader, maxPayloadBytes);
    }

    /** Resolve a lowercase hexadecimal prefix across all object types, then verify the object. */
    public String resolve(String prefix) throws IOException {
        if (prefix == null || !prefix.matches("[0-9a-f]{1,64}"))
            throw new IOException("invalid object ID prefix");
        paths.checkBase();
        String match = null;
        try (var directories =
                java.nio.file.Files.newDirectoryStream(repository.objectsDirectory())) {
            for (Path directory : directories) {
                String part = directory.getFileName().toString();
                if (!part.matches("[0-9a-f]{2}")
                        || !(part.startsWith(prefix) || prefix.startsWith(part))) continue;
                paths.checkPrefix(part + "0".repeat(62));
                try (var objects = java.nio.file.Files.newDirectoryStream(directory)) {
                    for (Path object : objects) {
                        String hash = part + object.getFileName();
                        if (!hash.matches("[0-9a-f]{64}") || !hash.startsWith(prefix)) continue;
                        var attributes =
                                java.nio.file.Files.readAttributes(
                                        object,
                                        java.nio.file.attribute.BasicFileAttributes.class,
                                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
                        if (!attributes.isRegularFile() || attributes.isSymbolicLink())
                            throw new IOException("object must be a regular file: " + hash);
                        if (match != null) throw new IOException("ambiguous object ID: " + prefix);
                        match = hash;
                    }
                }
            }
        }
        if (match == null) throw new ObjectNotFoundException(prefix);
        verify(match);
        return match;
    }

    public Path pathForHash(String hash) {
        return paths.pathForHash(hash);
    }

    public String write(ObjectType type, byte[] payload) throws IOException {
        return writer.write(type, payload);
    }

    public String writeBlob(Blob blob) throws IOException {
        return write(ObjectType.BLOB, blob.content());
    }

    public StoredObject read(String hash) throws IOException {
        return reader.read(hash);
    }

    public StoredObject readTree(String hash) throws IOException {
        return reader.read(hash, ObjectType.TREE);
    }

    public StoredObject readCommit(String hash) throws IOException {
        return reader.read(hash, ObjectType.COMMIT);
    }

    public ObjectReader.Summary verify(String hash) throws IOException {
        return reader.verify(hash);
    }

    public ObjectReader.Summary verifyBlob(String hash) throws IOException {
        var summary = verify(hash);
        if (summary.type() != ObjectType.BLOB)
            throw new IOException("object is not a blob: " + hash);
        return summary;
    }

    /** Caller owns the input stream; publication requires exactly the declared bytes. */
    public String writeBlob(long size, InputStream input) throws IOException {
        return writer.write(ObjectType.BLOB, size, input);
    }

    /** Copied bytes must remain private until full object validation succeeds. */
    public ObjectReader.Summary copyBlob(String hash, OutputStream output) throws IOException {
        return reader.copy(hash, ObjectType.BLOB, output);
    }

    public ObjectReader.Summary copy(String hash, ObjectType type, OutputStream output)
            throws IOException {
        return reader.copy(hash, type, output);
    }

    public Blob readBlob(String hash) throws IOException {
        StoredObject object = reader.read(hash, ObjectType.BLOB);
        return new Blob(object.payload());
    }
}
