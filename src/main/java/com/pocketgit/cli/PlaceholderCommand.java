package com.pocketgit.cli;

import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Unmatched;
import picocli.CommandLine.Model.CommandSpec;

/** Honest CLI surface for phases not implemented yet; never changes repository state. */
@Command(mixinStandardHelpOptions = true)
public final class PlaceholderCommand implements Callable<Integer> {
    @Spec private CommandSpec spec;
    @Unmatched private List<String> arguments;

    @Override public Integer call() {
        spec.commandLine().getErr().println("Not implemented yet.");
        return 3;
    }
}
