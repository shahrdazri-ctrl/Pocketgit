package com.pocketgit.cli;

import com.pocketgit.services.RestoreService;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

@Command(name="restore",mixinStandardHelpOptions=true,description="Replace a working file from the index or a commit; discards that file's unstaged changes.")
public final class RestoreCommand implements Callable<Integer> {
    private final Path cwd; private final RestoreService service;
    @Option(names="--commit",paramLabel="COMMIT") private String commit;
    @Parameters(index="0",paramLabel="FILE") private Path file;
    @Spec private CommandSpec spec;
    public RestoreCommand(Path cwd,RestoreService service){this.cwd=cwd;this.service=service;}
    @Override public Integer call() throws Exception {spec.commandLine().getOut().println("Restored "+service.restore(cwd,file,commit));return 0;}
}
