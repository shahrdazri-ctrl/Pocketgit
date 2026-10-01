package com.pocketgit.cli;

import com.pocketgit.diff.DiffFormatter;
import com.pocketgit.services.DiffService;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

@Command(
        name = "diff",
        mixinStandardHelpOptions = true,
        description = "Show unstaged changes, or HEAD versus the index with --staged.")
public final class DiffCommand implements Callable<Integer> {
    private final Path cwd;
    private final DiffService service;

    @Option(names = "--staged")
    private boolean staged;

    @Spec private CommandSpec spec;

    public DiffCommand(Path cwd, DiffService service) {
        this.cwd = cwd;
        this.service = service;
    }

    @Override
    public Integer call() throws Exception {
        new DiffFormatter().write(service.diff(cwd, staged), spec.commandLine().getOut());
        spec.commandLine().getOut().flush();
        if (spec.commandLine().getOut().checkError())
            throw new java.io.IOException("could not write diff output");
        return 0;
    }
}
