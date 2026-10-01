package com.pocketgit.storage;

import com.pocketgit.model.ObjectType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.DeflaterOutputStream;

/** Fully writes a private temporary file, then exclusively publishes an immutable object. */
public final class ObjectWriter {
    private final ObjectPaths paths;
    private final ObjectReader reader;
    private final int maxPayloadBytes;
    private final ObjectHasher hasher = new ObjectHasher();

    ObjectWriter(ObjectPaths paths, ObjectReader reader, int maxPayloadBytes) {
        this.paths = paths;
        this.reader = reader;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public String write(ObjectType type, byte[] payload) throws IOException {
        if (payload.length > maxPayloadBytes)
            throw new IOException(
                    "object exceeds configured payload limit of " + maxPayloadBytes + " bytes");
        // Snapshot mutable caller data once so the hash and written bytes always agree.
        byte[] canonical = hasher.canonicalBytes(type, payload);
        String hash = hasher.hashCanonical(canonical);
        paths.preparePrefix(hash);
        Path destination = paths.pathForHash(hash);
        // Verify, rather than silently trust, already stored objects.
        try {
            Files.readAttributes(
                    destination,
                    java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            verifyExisting(hash, canonical);
            return hash;
        } catch (java.nio.file.NoSuchFileException absent) {
            /* Publish a new object below. */
        }
        Path temporary = Files.createTempFile(destination.getParent(), ".tmp-", ".object");
        Throwable failure = null;
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE);
                    DeflaterOutputStream compressed =
                            new DeflaterOutputStream(Channels.newOutputStream(channel))) {
                compressed.write(canonical);
                compressed.finish();
                channel.force(true);
            }
            try {
                // A hard link atomically publishes complete bytes and never replaces a winner.
                // ATOMIC_MOVE can replace an existing target on some providers, so it is
                // unsuitable.
                Files.createLink(destination, temporary);
            } catch (FileAlreadyExistsException raceWinner) {
                verifyExisting(hash, canonical);
            } catch (UnsupportedOperationException unsupported) {
                throw new IOException(
                        "object publication requires filesystem hard-link support", unsupported);
            }
            return hash;
        } catch (IOException | RuntimeException problem) {
            failure = problem;
            throw problem;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }

    private void verifyExisting(String hash, byte[] expectedCanonical) throws IOException {
        var existing = reader.read(hash);
        byte[] actual = hasher.canonicalBytes(existing.type(), existing.payload());
        if (!Arrays.equals(expectedCanonical, actual)) {
            throw new CorruptObjectException(
                    "existing object differs from requested immutable content: " + hash);
        }
    }

    /** Bounded streaming publication; incomplete input never becomes a visible object. */
    public String write(ObjectType type, long size, InputStream input) throws IOException {
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(input, "input");
        if (size < 0 || size > maxPayloadBytes) {
            throw new IOException(
                    "object exceeds configured payload limit of " + maxPayloadBytes + " bytes");
        }
        Path temporary = Files.createTempFile(paths.temporaryDirectory(), ".tmp-", ".object");
        Throwable failure = null;
        try {
            var digest = ObjectReader.sha256();
            byte[] header = (type.token() + " " + size + "\0").getBytes(StandardCharsets.US_ASCII);
            digest.update(header);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE);
                    var compressed = new DeflaterOutputStream(Channels.newOutputStream(channel))) {
                compressed.write(header);
                byte[] buffer = new byte[16 * 1024];
                long copied = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count > size - copied)
                        throw new IOException("payload length changed during object write");
                    copied += count;
                    digest.update(buffer, 0, count);
                    compressed.write(buffer, 0, count);
                }
                if (copied != size)
                    throw new IOException("payload length changed during object write");
                compressed.finish();
                channel.force(true);
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            paths.preparePrefix(hash);
            try {
                Files.createLink(paths.pathForHash(hash), temporary);
            } catch (FileAlreadyExistsException existing) {
                var stored = reader.verify(hash);
                if (stored.type() != type || stored.size() != size) {
                    throw new CorruptObjectException(
                            "existing object differs from requested immutable content: " + hash);
                }
            } catch (UnsupportedOperationException unsupported) {
                throw new IOException(
                        "object publication requires filesystem hard-link support", unsupported);
            }
            return hash;
        } catch (IOException | RuntimeException problem) {
            failure = problem;
            throw problem;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }
}
