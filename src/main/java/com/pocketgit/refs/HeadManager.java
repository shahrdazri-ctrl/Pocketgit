package com.pocketgit.refs;

import com.pocketgit.repository.Repository;
import com.pocketgit.storage.MetadataFiles;
import com.pocketgit.util.HashUtils;
import com.pocketgit.validation.RefNameValidator;
import java.io.IOException;

/** Parses symbolic and detached HEAD without changing repository state. */
public final class HeadManager {
    public record Head(String branch, String commitHash) {
        public Head {
            if ((branch == null) == (commitHash == null))
                throw new IllegalArgumentException("HEAD requires exactly one target");
            if (branch != null) RefNameValidator.validateBranch(branch);
            else HashUtils.validateSha256(commitHash);
        }

        public String resolve(RefStore refs) throws IOException {
            return branch == null ? commitHash : refs.readBranch(branch);
        }
    }

    private final Repository repository;
    private final MetadataFiles files;

    public HeadManager(Repository repository) {
        this.repository = repository;
        files = new MetadataFiles(repository);
    }

    public Head read() throws IOException {
        String value = files.readText(repository.headFile(), 4096);
        if (value.endsWith("\n")) value = value.substring(0, value.length() - 1);
        String prefix = "ref: refs/heads/";
        try {
            return value.startsWith(prefix)
                    ? new Head(value.substring(prefix.length()), null)
                    : new Head(null, value);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid HEAD reference", invalid);
        }
    }

    public String readBranch() throws IOException {
        var head = read();
        if (head.branch() == null)
            throw new IOException("this operation requires a branch; detached HEAD is unsupported");
        return head.branch();
    }
}
