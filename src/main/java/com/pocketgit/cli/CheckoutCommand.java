package com.pocketgit.cli;

import com.pocketgit.services.CheckoutService;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name="checkout", mixinStandardHelpOptions=true, description="Safely switch to an existing branch.")
public final class CheckoutCommand implements Callable<Integer> {
    private final Path cwd; private final CheckoutService service;
    @Parameters(index="0",paramLabel="BRANCH") private String branch;
    @Spec private CommandSpec spec;
    public CheckoutCommand(Path cwd, CheckoutService service) { this.cwd=cwd; this.service=service; }
    @Override public Integer call() throws Exception {
        var result=service.checkout(cwd,branch);
        spec.commandLine().getOut().println(result.changed() ? "Switched to branch '"+branch+"'" : "Already on '"+branch+"'"); return 0;
    }
}
