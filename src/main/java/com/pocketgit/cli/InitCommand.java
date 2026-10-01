package com.pocketgit.cli;

import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.util.TerminalText;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "init",
        mixinStandardHelpOptions = true,
        description = "Initialize a PocketGit repository in an existing directory.")
public final class InitCommand implements Callable<Integer> {
    private final Path workingDirectory;
    private final RepositoryInitializer initializer;

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "DIRECTORY",
            description = "Existing directory (default: current directory).")
    private Path directory;

    @Spec private CommandSpec spec;

    public InitCommand(Path workingDirectory, RepositoryInitializer initializer) {
        this.workingDirectory = workingDirectory;
        this.initializer = initializer;
    }

    @Override
    public Integer call() throws Exception {
        Path target = directory == null ? workingDirectory : workingDirectory.resolve(directory);
        var result = initializer.initialize(target);
        spec.commandLine()
                .getOut()
                .println(
                        result.created()
                                ? "Initialized empty PocketGit repository in "
                                        + TerminalText.escapeLabel(
                                                result.repository().metadataDirectory().toString())
                                : "PocketGit repository already exists at "
                                        + TerminalText.escapeLabel(
                                                result.repository().root().toString()));
        return 0;
    }
}
