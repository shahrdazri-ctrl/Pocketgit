package com.pocketgit.cli;

import com.pocketgit.validation.IntegrityChecker;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

@Command(name="verify",mixinStandardHelpOptions=true,description="Check HEAD, refs, all stored objects, reachable snapshots, and the index.")
public final class VerifyCommand implements Callable<Integer> {
    private final Path cwd;private final IntegrityChecker checker;
    @Spec private CommandSpec spec;
    public VerifyCommand(Path cwd,IntegrityChecker checker){this.cwd=cwd;this.checker=checker;}
    @Override public Integer call()throws Exception {
        var report=checker.verify(cwd);var out=spec.commandLine().getOut();out.println("Checking repository...");
        if(report.valid()) {
            out.println("✓ HEAD\n✓ refs\n✓ commits\n✓ trees\n✓ blobs\n✓ index");
            out.printf("Repository OK. (%d objects: %d commits, %d trees, %d blobs)%n",report.objects(),report.commits(),report.trees(),report.blobs());
        }else {for(String error:report.errors())out.println("ERROR "+error);out.println("Repository integrity check failed.");}
        return report.valid()?0:1;
    }
}
