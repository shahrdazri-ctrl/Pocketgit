package com.pocketgit.unit;

import com.pocketgit.model.*;
import com.pocketgit.refs.*;
import com.pocketgit.repository.*;
import com.pocketgit.services.*;
import com.pocketgit.storage.*;
import com.pocketgit.util.FileModeUtils;
import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CheckoutServiceTest {
    @TempDir Path root; private Repository repo;
    private final CheckoutService checkout = new CheckoutService();
    @BeforeEach void initialize() throws Exception {
        repo=new RepositoryInitializer().initialize(root).repository();
        new ConfigStore(repo).set("user.name","Reviewer"); new ConfigStore(repo).set("user.email","reviewer@example.com");
    }
    private void file(String name,String text) throws Exception { Path path=root.resolve(name); Files.createDirectories(path.getParent()); Files.writeString(path,text); }
    private void commit(String message) throws Exception { new AddService().add(root,Path.of(".")); new CommitService().commit(root,message,false); }
    private void branch() throws Exception { new BranchService().create(root,"feature"); }
    private Map<String,byte[]> state() throws Exception {
        var result=new TreeMap<String,byte[]>(); try(var paths=Files.walk(root)) { for(var path:paths.filter(Files::isRegularFile).toList()) result.put(root.relativize(path).toString(),Files.readAllBytes(path)); } return result;
    }
    private void same(Map<String,byte[]> before) throws Exception { var after=state(); assertEquals(before.keySet(),after.keySet()); before.forEach((p,b)->assertArrayEquals(b,after.get(p),p)); }
    @Test void switchesFilesAddsDeletesAndUpdatesIndexHeadAndReflog() throws Exception {
        file("src/old","old"); file("same","unchanged"); commit("initial"); branch();
        Files.delete(root.resolve("src/old")); file("new/path","new"); commit("second");
        file("private","untracked"); file("same","unrelated local edit");
        assertTrue(checkout.checkout(root,"feature").changed());
        assertEquals("old",Files.readString(root.resolve("src/old"))); assertFalse(Files.exists(root.resolve("new/path")));
        assertEquals("untracked",Files.readString(root.resolve("private"))); assertEquals("unrelated local edit",Files.readString(root.resolve("same")));
        assertEquals("feature",new HeadManager(repo).readBranch());
        assertEquals(new HeadSnapshotReader().read(repo).index(),new IndexStore(repo).load());
        assertTrue(Files.readString(repo.logsDirectory().resolve("HEAD")).endsWith(" checkout\n"));
        checkout.checkout(root,"main"); assertEquals("new",Files.readString(root.resolve("new/path"))); assertFalse(Files.exists(root.resolve("src/old")));
    }
    @Test void unstagedEditOrDeletionConflictMakesZeroChanges() throws Exception {
        file("a","first"); commit("first"); branch(); file("a","second"); commit("second");
        file("a","private local edit"); var before=state(); assertThrows(CheckoutConflictException.class,()->checkout.checkout(root,"feature")); same(before);
        Files.delete(root.resolve("a")); before=state(); assertThrows(CheckoutConflictException.class,()->checkout.checkout(root,"feature")); same(before);
    }
    @Test void stagedEditsCannotBeLost() throws Exception {
        file("a","first"); commit("first"); branch(); file("b","staged"); new AddService().add(root,Path.of("b"));
        var before=state(); assertThrows(CheckoutConflictException.class,()->checkout.checkout(root,"feature")); same(before);
    }
    @Test void untrackedAndIgnoredTargetFilesAreProtected() throws Exception {
        file("a","base"); file("secret","tracked first"); commit("first"); branch(); Files.delete(root.resolve("secret")); commit("delete");
        file("secret","private"); var before=state(); assertThrows(CheckoutConflictException.class,()->checkout.checkout(root,"feature")); same(before);
        file(".pocketgitignore","secret\n"); before=state(); assertThrows(CheckoutConflictException.class,()->checkout.checkout(root,"feature")); same(before);
    }
    @Test void sameBranchDoesNotRewriteLocalWork() throws Exception {
        file("a","base"); commit("base"); file("a","local edit"); var before=state();
        assertFalse(checkout.checkout(root,"main").changed()); same(before);
    }
    @Test void fileDirectoryTransitionsAreReversible() throws Exception {
        file("a","file"); commit("file"); branch(); Files.delete(root.resolve("a")); file("a/b/c","nested"); commit("directory");
        checkout.checkout(root,"feature"); assertEquals("file",Files.readString(root.resolve("a")));
        checkout.checkout(root,"main"); assertEquals("nested",Files.readString(root.resolve("a/b/c")));
    }
    @Test void untrackedDescendantPreventsDirectoryReplacement() throws Exception {
        file("a","file"); commit("file"); branch(); Files.delete(root.resolve("a")); file("a/tracked","tracked"); commit("directory");
        file("a/private","do not delete"); var before=state(); assertThrows(CheckoutConflictException.class,()->checkout.checkout(root,"feature")); same(before);
    }
    @Test void untrackedFileObstructingParentDirectoryIsProtected() throws Exception {
        file("a/b","nested"); commit("directory"); branch(); Files.delete(root.resolve("a/b")); Files.delete(root.resolve("a")); commit("empty");
        file("a","private"); var before=state(); assertThrows(CheckoutConflictException.class,()->checkout.checkout(root,"feature")); same(before);
    }
    @Test void missingBranchAndCorruptTargetDoNotTouchFiles() throws Exception {
        file("a","base"); commit("base"); branch(); var before=state();
        assertThrows(IOException.class,()->checkout.checkout(root,"missing")); assertThrows(IOException.class,()->checkout.checkout(root,"../escape")); same(before);
        String hash=new RefStore(repo).readBranch("feature"); var objects=new ObjectStore(repo); Files.writeString(objects.pathForHash(hash),"corrupt"); before=state();
        assertThrows(IOException.class,()->checkout.checkout(root,"feature")); same(before);
    }
    @Test void failureAfterFileEditsRollsBackContentModesDirectoriesAndMetadata() throws Exception {
        file("a","file"); file("deleted","first"); commit("first"); branch(); Files.delete(root.resolve("a")); Files.delete(root.resolve("deleted")); file("a/b","directory"); commit("second");
        var before=state(); var failing=new CheckoutService(Clock.systemUTC(),()->{throw new IOException("injected failure");});
        assertThrows(IOException.class,()->failing.checkout(root,"feature")); same(before);
        assertEquals("main",new HeadManager(repo).readBranch()); assertTrue(Files.isDirectory(root.resolve("a")));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={2,3,4})
    void failureAfterEachMetadataPublicationRestoresOriginalBytes(int failAt) throws Exception {
        file("a","first"); commit("first"); branch(); file("a","second"); commit("second");
        var before=state(); var calls=new java.util.concurrent.atomic.AtomicInteger();
        var failing=new CheckoutService(Clock.systemUTC(),()->{if(calls.incrementAndGet()==failAt)throw new IOException("publication failure");});
        assertThrows(IOException.class,()->failing.checkout(root,"feature")); same(before);
    }
    @Test void preservesExecutableDistinction() throws Exception {
        file("script","echo test"); assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        Files.setPosixFilePermissions(root.resolve("script"),java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")); commit("executable"); branch();
        Files.setPosixFilePermissions(root.resolve("script"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--")); commit("regular");
        checkout.checkout(root,"feature"); assertEquals(FileMode.EXECUTABLE_FILE,FileModeUtils.fileMode(root.resolve("script")));
        checkout.checkout(root,"main"); assertEquals(FileMode.REGULAR_FILE,FileModeUtils.fileMode(root.resolve("script")));
    }
    @Test void symlinkObstructionsCannotWriteOutsideRepository() throws Exception {
        file("a/b","nested"); commit("first"); branch(); Files.delete(root.resolve("a/b")); Files.delete(root.resolve("a")); commit("delete");
        Path outside=Files.createTempDirectory(root.getParent(),"checkout-outside");
        try { Files.createSymbolicLink(root.resolve("a"),outside); } catch(IOException|UnsupportedOperationException failure) { assumeTrue(false,"symlinks unavailable"); }
        assertThrows(IOException.class,()->checkout.checkout(root,"feature")); try(var files=Files.list(outside)) { assertEquals(0,files.count()); }
        assertEquals("main",new HeadManager(repo).readBranch());
    }
}
