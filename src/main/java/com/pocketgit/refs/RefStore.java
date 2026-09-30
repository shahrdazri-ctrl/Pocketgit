package com.pocketgit.refs;

import com.pocketgit.repository.Repository;
import com.pocketgit.storage.MetadataFiles;
import com.pocketgit.util.HashUtils;
import com.pocketgit.validation.RefNameValidator;
import com.pocketgit.storage.ObjectStore;
import com.pocketgit.storage.ObjectCodec;
import com.pocketgit.services.TreeReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/** Existing branch refs only; branch creation/listing are Phase 6. Caller holds commit lock. */
public final class RefStore {
    private final Repository repository;
    private final MetadataFiles files;
    public RefStore(Repository repository) { this.repository = repository; files = new MetadataFiles(repository); }
    private Path path(String branch) throws IOException {
        try { RefNameValidator.validateBranch(branch); }
        catch (IllegalArgumentException invalid) { throw new IOException("invalid branch reference", invalid); }
        return repository.headsDirectory().resolve(branch);
    }
    public String readBranch(String branch) throws IOException {
        String value = files.readText(path(branch), 65);
        if (value.isEmpty()) return null;
        if (value.endsWith("\n")) value = value.substring(0, value.length() - 1);
        try { HashUtils.validateSha256(value); }
        catch (IllegalArgumentException invalid) { throw new IOException("invalid branch commit ID: " + branch, invalid); }
        return value;
    }
    public void requireUnchanged(String branch, String expected) throws IOException {
        if (!Objects.equals(readBranch(branch), expected)) throw new IOException("branch changed during commit; retry after inspecting repository");
    }
    public MetadataFiles.Prepared prepare(String branch, String expected, String next) throws IOException {
        requireUnchanged(branch, expected);
        HashUtils.validateSha256(next);
        var objects = new ObjectStore(repository);
        var codec = new ObjectCodec();
        var commit = codec.decodeCommit(objects.read(next));
        new TreeReader(objects).readSnapshot(commit.treeHash());
        for (String parent : commit.parentHashes()) codec.decodeCommit(objects.read(parent));
        return files.prepare(path(branch), (next + "\n").getBytes(StandardCharsets.US_ASCII));
    }
}
