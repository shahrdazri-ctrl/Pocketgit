package com.pocketgit.refs;

import com.pocketgit.repository.Repository;
import com.pocketgit.storage.MetadataFiles;
import com.pocketgit.validation.RefNameValidator;
import java.io.IOException;

/** Phase 4 commits require symbolic HEAD; detached operation arrives with history/ref work. */
public final class HeadManager {
    private final Repository repository;
    private final MetadataFiles files;
    public HeadManager(Repository repository) { this.repository = repository; files = new MetadataFiles(repository); }
    public String readBranch() throws IOException {
        String value = files.readText(repository.headFile(), 4096);
        if (value.endsWith("\n")) value = value.substring(0, value.length() - 1);
        String prefix = "ref: refs/heads/";
        if (!value.startsWith(prefix)) throw new IOException("HEAD must reference a branch; detached or malformed HEAD is unsupported in this phase");
        String branch = value.substring(prefix.length());
        try { RefNameValidator.validateBranch(branch); }
        catch (IllegalArgumentException invalid) { throw new IOException("invalid HEAD branch reference", invalid); }
        return branch;
    }
}
