package com.pocketgit.integration;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiffRestoreIT {
    @TempDir Path temp;
    @Test void realDiffStageCommitAndRestoreWorkflow()throws Exception {
        var cli=new CliProcess(temp);Path root=cli.initialize();Files.writeString(root.resolve("a"),"first\n");cli.success(root,"add",".");cli.success(root,"commit","-m","first");
        String first=Files.readString(root.resolve(".pocketgit/refs/heads/main")).strip();
        Files.writeString(root.resolve("a"),"staged\n");cli.success(root,"add","a");Files.writeString(root.resolve("a"),"working\n");
        assertTrue(cli.success(root,"diff").out().contains("-staged\n+working\n"));assertTrue(cli.success(root,"diff","--staged").out().contains("-first\n+staged\n"));
        byte[] index=Files.readAllBytes(root.resolve(".pocketgit/index"));cli.success(root,"restore","a");assertEquals("staged\n",Files.readString(root.resolve("a")));
        cli.success(root,"restore","--commit",first.substring(0,12),"a");assertEquals("first\n",Files.readString(root.resolve("a")));assertArrayEquals(index,Files.readAllBytes(root.resolve(".pocketgit/index")));
        var bad=cli.run(root,"restore","../escape");assertEquals(1,bad.code());assertEquals("",bad.out());assertFalse(bad.err().contains("\tat "));
        assertEquals(2,cli.run(root,"restore").code());assertEquals(2,cli.run(root,"diff","--unknown").code());
    }
    @Test void realBinaryDiffAndByteExactRestore()throws Exception {
        var cli=new CliProcess(temp);Path root=cli.initialize();byte[] first={0,1,2,(byte)255};Files.write(root.resolve("binary"),first);cli.success(root,"add",".");
        Files.write(root.resolve("binary"),new byte[]{0,3,4});assertTrue(cli.success(root,"diff").out().contains("Binary files differ: binary"));
        cli.success(root,"restore","binary");assertArrayEquals(first,Files.readAllBytes(root.resolve("binary")));
    }
}
