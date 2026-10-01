package com.pocketgit.util;

import com.pocketgit.model.FileMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;

public final class FileModeUtils {
    private FileModeUtils() {}

    public static FileMode fileMode(Path file) throws IOException {
        var view =
                Files.getFileAttributeView(
                        file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) return FileMode.REGULAR_FILE;
        var permissions = view.readAttributes().permissions();
        return permissions.contains(PosixFilePermission.OWNER_EXECUTE)
                        || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                        || permissions.contains(PosixFilePermission.OTHERS_EXECUTE)
                ? FileMode.EXECUTABLE_FILE
                : FileMode.REGULAR_FILE;
    }
}
