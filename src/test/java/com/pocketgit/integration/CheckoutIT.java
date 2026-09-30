package com.pocketgit.integration;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CheckoutIT {
    @TempDir Path temp;
    private String run(Path root,int expected,String... args) throws Exception {
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-jar",Path.of(System.getProperty("pocketgit.jar")).toAbsolutePath().toString())); command.addAll(List.of(args));
        Path out=temp.resolve("output"); var builder=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(out.toFile());
        builder.environment().remove("POCKETGIT_AUTHOR_NAME"); builder.environment().remove("POCKETGIT_AUTHOR_EMAIL");
        var process=builder.start(); boolean done=process.waitFor(20,TimeUnit.SECONDS); if(!done)process.destroyForcibly(); assertTrue(done);
        String output=Files.readString(out); assertEquals(expected,process.exitValue(),output); assertFalse(output.contains("\tat ")); return output;
    }
    @Test void realBranchSwitchAndConflictPreservation() throws Exception {
        Path root=Files.createDirectory(temp.resolve("repo")); run(root,0,"init"); run(root,0,"config","user.name","Reviewer"); run(root,0,"config","user.email","reviewer@example.com");
        Files.writeString(root.resolve("a"),"first"); run(root,0,"add","."); run(root,0,"commit","-m","first"); run(root,0,"branch","feature");
        Files.writeString(root.resolve("a"),"second"); Files.writeString(root.resolve("new"),"new"); run(root,0,"add","."); run(root,0,"commit","-m","second");
        Files.writeString(root.resolve("a"),"private"); byte[] index=Files.readAllBytes(root.resolve(".pocketgit/index"));
        assertTrue(run(root,1,"checkout","feature").contains("would be overwritten")); assertEquals("private",Files.readString(root.resolve("a"))); assertArrayEquals(index,Files.readAllBytes(root.resolve(".pocketgit/index")));
        Files.writeString(root.resolve("a"),"second"); assertTrue(run(root,0,"checkout","feature").contains("Switched to branch 'feature'"));
        assertEquals("first",Files.readString(root.resolve("a"))); assertFalse(Files.exists(root.resolve("new")));
        assertTrue(run(root,0,"status").contains("working tree clean")); run(root,0,"checkout","main"); assertEquals("second",Files.readString(root.resolve("a")));
    }
}
