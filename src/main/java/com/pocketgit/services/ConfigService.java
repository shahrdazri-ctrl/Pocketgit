package com.pocketgit.services;

import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryLocator;
import com.pocketgit.storage.ConfigStore;
import java.io.IOException;
import java.nio.file.Path;

public final class ConfigService {
    private ConfigStore store(Path cwd) throws IOException {
        var root = new RepositoryLocator().findRepositoryRoot(cwd)
                .orElseThrow(() -> new IOException("not a PocketGit repository"));
        return new ConfigStore(new Repository(root));
    }
    public String get(Path cwd, String key) throws IOException { return store(cwd).get(key); }
    public void set(Path cwd, String key, String value) throws IOException { store(cwd).set(key, value); }
}
