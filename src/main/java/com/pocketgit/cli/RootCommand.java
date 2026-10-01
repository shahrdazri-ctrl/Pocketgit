package com.pocketgit.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(
        name = "pocketgit",
        mixinStandardHelpOptions = true,
        version = "PocketGit 1.0.0",
        description =
                "A Java version-control engine with verified snapshots and safe working-tree"
                        + " operations.")
public final class RootCommand implements Runnable {
    @Spec private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
