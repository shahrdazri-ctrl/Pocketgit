package com.pocketgit.cli;

import com.pocketgit.refs.BranchService;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "branch", mixinStandardHelpOptions = true, description = "List branches or create a branch at the current commit.")
public final class BranchCommand implements Callable<Integer> {
    private final Path cwd;
    private final BranchService service;
    @Parameters(index = "0", arity = "0..1", paramLabel = "NAME", description = "New branch name; omit to list branches.") private String name;
    @Spec private CommandSpec spec;
    public BranchCommand(Path cwd, BranchService service) { this.cwd = cwd; this.service = service; }
    @Override public Integer call() throws Exception {
        var output = spec.commandLine().getOut();
        if (name != null) {
            service.create(cwd, name);
            output.println("Created branch '" + name + "'.");
        } else {
            var branches = service.list(cwd);
            for (String branch : branches.names()) output.println((branch.equals(branches.current()) ? "* " : "  ") + branch);
        }
        output.flush();
        return 0;
    }
}
