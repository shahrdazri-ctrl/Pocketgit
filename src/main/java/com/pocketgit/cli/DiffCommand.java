package com.pocketgit.cli;

import com.pocketgit.services.DiffService;
import com.pocketgit.diff.DiffFormatter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

@Command(name="diff",mixinStandardHelpOptions=true,description="Show unstaged changes, or HEAD versus the index with --staged.")
public final class DiffCommand implements Callable<Integer> {
    private final Path cwd; private final DiffService service;
    @Option(names="--staged") private boolean staged;
    @Spec private CommandSpec spec;
    public DiffCommand(Path cwd,DiffService service){this.cwd=cwd;this.service=service;}
    @Override public Integer call() throws Exception {spec.commandLine().getOut().print(new DiffFormatter().format(service.diff(cwd,staged)));spec.commandLine().getOut().flush();return 0;}
}
