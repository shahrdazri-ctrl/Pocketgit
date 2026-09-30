package com.pocketgit.unit;

import com.pocketgit.model.Blob;
import com.pocketgit.repository.*;
import com.pocketgit.services.*;
import com.pocketgit.storage.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DiffRestoreServiceTest {
    @TempDir Path root;private Repository repo;private final RestoreService restore=new RestoreService();private final DiffService diff=new DiffService();
    @BeforeEach void initialize()throws Exception {repo=new RepositoryInitializer().initialize(root).repository();new ConfigStore(repo).set("user.name","Reviewer");new ConfigStore(repo).set("user.email","reviewer@example.com");}
    private void file(String path,String text)throws Exception {Path target=root.resolve(path);Files.createDirectories(target.getParent());Files.writeString(target,text);}
    private void stage()throws Exception {new AddService().add(root,Path.of("."));}
    private String commit()throws Exception {stage();return new CommitService().commit(root,"test",false).commitHash();}
    private Map<String,byte[]> metadata()throws Exception {var result=new TreeMap<String,byte[]>();try(var paths=Files.walk(repo.metadataDirectory())){for(var p:paths.filter(Files::isRegularFile).toList())result.put(repo.metadataDirectory().relativize(p).toString(),Files.readAllBytes(p));}return result;}
    private void same(Map<String,byte[]> before)throws Exception {var after=metadata();assertEquals(before.keySet(),after.keySet());before.forEach((p,b)->assertArrayEquals(b,after.get(p),p));}
    @Test void stagedAndUnstagedAreSeparateAndDiffIsReadOnly()throws Exception {
        file("a","first\n");commit();file("a","staged\n");stage();file("a","working\n");file("untracked","private");var before=metadata();
        var staged=diff.diff(root,true);var working=diff.diff(root,false);assertEquals(1,staged.size());assertEquals(1,working.size());
        assertTrue(staged.getFirst().hunks().getFirst().lines().stream().anyMatch(l->l.text().equals("staged")));
        assertTrue(working.getFirst().hunks().getFirst().lines().stream().anyMatch(l->l.text().equals("working")));same(before);
    }
    @Test void diffHandlesUnbornStagedAdditionsAndTrackedDeletions()throws Exception {
        file("a","new\n");stage();assertFalse(diff.diff(root,true).getFirst().oldExists());commit();Files.delete(root.resolve("a"));
        assertFalse(diff.diff(root,false).getFirst().newExists());stage();assertFalse(diff.diff(root,true).getFirst().newExists());
    }
    @Test void indexAndCommitRestoreAreExactAndLeaveMetadataUnchanged()throws Exception {
        file("a","historical");String first=commit();file("a","staged");stage();file("a","private edit");var before=metadata();
        restore.restore(root,Path.of("a"),null);assertEquals("staged",Files.readString(root.resolve("a")));same(before);
        restore.restore(root,Path.of("a"),first.substring(0,12));assertEquals("historical",Files.readString(root.resolve("a")));same(before);
        assertFalse(diff.diff(root,false).isEmpty());
    }
    @Test void binaryRestoreCreatesMissingParentDirectories()throws Exception {
        Files.createDirectories(root.resolve("dir"));byte[] binary=new byte[]{0,1,2,(byte)255,4};Files.write(root.resolve("dir/binary"),binary);stage();
        Files.delete(root.resolve("dir/binary"));Files.delete(root.resolve("dir"));var before=metadata();restore.restore(root,Path.of("dir/binary"),null);
        assertArrayEquals(binary,Files.readAllBytes(root.resolve("dir/binary")));same(before);
    }
    @ParameterizedTest @ValueSource(strings={"../escape","a/../a",".pocketgit/HEAD","missing","."})
    void unsafeAndMissingPathsFailWithoutMetadataChanges(String path)throws Exception {
        file("a","private");stage();var before=metadata();assertThrows(IOException.class,()->restore.restore(root,Path.of(path),null));same(before);assertEquals("private",Files.readString(root.resolve("a")));
    }
    @Test void directoriesAndParentFileObstructionsAreRejected()throws Exception {
        file("a/b","tracked");stage();Files.delete(root.resolve("a/b"));Files.delete(root.resolve("a"));file("a","private");
        assertThrows(IOException.class,()->restore.restore(root,Path.of("a/b"),null));assertEquals("private",Files.readString(root.resolve("a")));
    }
    @Test void corruptSourceNeverOverwritesWorkingContent()throws Exception {
        file("a","original");stage();var entry=new IndexStore(repo).load().entries().getFirst();Files.writeString(new ObjectStore(repo).pathForHash(entry.blobHash()),"corrupt");file("a","private");var before=metadata();
        assertThrows(IOException.class,()->restore.restore(root,Path.of("a"),null));assertEquals("private",Files.readString(root.resolve("a")));same(before);
    }
    @Test void symlinkTargetsAreRejectedWithoutOutsideWrites()throws Exception {
        file("a","tracked");stage();Files.delete(root.resolve("a"));Path outside=Files.writeString(root.resolve("outside"),"private");
        try{Files.createSymbolicLink(root.resolve("a"),outside);}catch(IOException|UnsupportedOperationException error){assumeTrue(false,"symlinks unavailable");}
        assertThrows(IOException.class,()->restore.restore(root,Path.of("a"),null));assertEquals("private",Files.readString(outside));
    }
    @Test void commitRestoreRejectsBlobIdsAndUnknownPrefixes()throws Exception {
        file("a","private");stage();String blob=new ObjectStore(repo).writeBlob(new Blob(new byte[]{2}));
        assertThrows(IOException.class,()->restore.restore(root,Path.of("a"),blob));assertThrows(IOException.class,()->restore.restore(root,Path.of("a"),"f".repeat(64)));assertEquals("private",Files.readString(root.resolve("a")));
    }
}
