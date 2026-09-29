package com.pocketgit.cli;

import com.pocketgit.services.AddService;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "add", mixinStandardHelpOptions = true, description = "Stage a file, directory, or the whole working tree with '.'.")
public final class AddCommand implements Callable<Integer> {
    private final Path workingDirectory;
    private final AddService service;
    @Parameters(index = "0", paramLabel = "PATH", description = "File/directory relative to the current directory; '.' stages the entire repository.")
    private Path path;
    @Spec private CommandSpec spec;

    public AddCommand(Path workingDirectory, AddService service) {
        this.workingDirectory = workingDirectory;
        this.service = service;
    }

    @Override public Integer call() throws Exception {
        var result = service.add(workingDirectory, path);
        var output = spec.commandLine().getOut();
        if (result.stagedPaths().size() == 1 && result.removedPaths().isEmpty()) output.println("Staged " + result.stagedPaths().getFirst());
        else output.println("Staged " + result.stagedPaths().size() + " files and " + result.removedPaths().size() + " deletions.");
        if (!result.ignoredPaths().isEmpty()) output.println("Skipped " + result.ignoredPaths().size() + " ignored paths.");
        return 0;
    }
}
