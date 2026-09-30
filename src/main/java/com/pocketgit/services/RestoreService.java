package com.pocketgit.services;

import com.pocketgit.model.*;
import com.pocketgit.repository.*;
import com.pocketgit.storage.*;
import com.pocketgit.util.PathUtils;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/** Restore modifies one explicitly requested working file, never the index or refs. */
public final class RestoreService {
    public String restore(Path cwd,Path requested,String commitPrefix) throws IOException {
        var repository=new Repository(new RepositoryLocator().findRepositoryRoot(cwd).orElseThrow(()->new IOException("not a PocketGit repository")));
        for(Path component:requested)if(component.toString().equals(".."))throw new IOException("restore rejects parent traversal");
        Path file=PathUtils.safeWorkingPath(repository.root(),cwd.toAbsolutePath().resolve(requested));
        String name;
        try{name=PathUtils.relativePath(repository.root(),file);}catch(IllegalArgumentException bad){throw new IOException("restore requires a file path",bad);}
        var files=new MetadataFiles(repository);
        try(var indexUpdate=new IndexStore(repository).beginUpdate();var lock=MetadataLock.acquire(files,repository.metadataDirectory().resolve("commit.lock"))) {
            Index source;
            if(commitPrefix==null)source=indexUpdate.load();
            else {
                var objects=new ObjectStore(repository); var commit=new ObjectCodec().decodeCommit(objects.read(objects.resolve(commitPrefix)));
                source=new TreeReader(objects).readSnapshot(commit.treeHash());
            }
            IndexEntry entry=CheckoutPlanner.entries(source).get(name);
            if(entry==null)throw new IOException("path not present in restore source: "+name);
            var attributes=PathUtils.attributesOrMissing(file);
            if(attributes!=null && !attributes.isRegularFile())throw new IOException("restore target must be a regular file: "+name);
            for(var parent=file.getParent();!parent.equals(repository.root());parent=parent.getParent()) {
                var a=PathUtils.attributesOrMissing(parent); if(a!=null && !a.isDirectory())throw new IOException("restore parent is not a directory: "+parent);
            }
            var edit=new WorkingTreeEdit(repository,List.of(entry),List.of());
            try{edit.apply();}catch(IOException|RuntimeException failure){try{edit.rollback();}catch(IOException rollback){failure.addSuppressed(rollback);}throw failure;}
            return name;
        }
    }
}
