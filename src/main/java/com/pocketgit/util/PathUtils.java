package com.pocketgit.util;

import com.pocketgit.repository.Repository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;

public final class PathUtils {
    private PathUtils() {}

    public static void validateIndexPath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\")
                || path.contains(":")) throw new IllegalArgumentException("invalid repository-relative path: " + path);
        for (char c : path.toCharArray()) {
            if (c < 32 || c == 127) throw new IllegalArgumentException("control character in repository path");
        }
        for (String component : path.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")
                    || component.equalsIgnoreCase(Repository.METADATA_NAME)) {
                throw new IllegalArgumentException("invalid repository-relative path: " + path);
            }
        }
    }

    /** Checks original components before normalization, including links canceled by '..'. */
    public static Path safeWorkingPath(Path root, Path requested) throws IOException {
        Path absolute = requested.toAbsolutePath();
        if (!absolute.startsWith(root) || !absolute.normalize().startsWith(root)) {
            throw new IOException("path is outside repository: " + requested);
        }
        Path cursor = root;
        boolean missingOrBlocked = false;
        if (absolute.getNameCount() == root.getNameCount()) return root;
        // subpath retains original '..' components; relativize may normalize them on some providers.
        for (Path part : absolute.subpath(root.getNameCount(), absolute.getNameCount())) {
            String component = part.toString();
            if (component.isEmpty() || component.equals(".")) continue;
            if (component.equalsIgnoreCase(Repository.METADATA_NAME)) throw new IOException("cannot stage PocketGit metadata");
            cursor = cursor.resolve(part).normalize();
            if (!cursor.startsWith(root)) throw new IOException("path is outside repository: " + requested);
            if (component.equals("..")) missingOrBlocked = false;
            if (missingOrBlocked) continue;
            try {
                var attributes = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink()) throw new IOException("symbolic links are not supported: " + cursor);
                if (!attributes.isDirectory()) missingOrBlocked = true;
            } catch (NoSuchFileException absent) { missingOrBlocked = true; }
        }
        return cursor;
    }

    /** A former directory replaced by a regular file makes its staged descendants absent. */
    public static BasicFileAttributes attributesOrMissing(Path path) throws IOException {
        try { return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException absent) { return null; }
        catch (IOException failure) {
            for (Path parent = path.getParent(); parent != null; parent = parent.getParent()) {
                BasicFileAttributes attributes;
                try { attributes = Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
                catch (NoSuchFileException absent) { continue; }
                catch (java.nio.file.FileSystemException blocked) { continue; }
                if (attributes.isRegularFile()) return null;
                throw failure;
            }
            throw failure;
        }
    }

    public static String relativePath(Path root, Path path) {
        var components = new ArrayList<String>();
        for (Path component : root.relativize(path.toAbsolutePath().normalize())) components.add(component.toString());
        String relative = String.join("/", components);
        validateIndexPath(relative);
        return relative;
    }

    public static boolean inScope(String path, String scope) {
        return scope.isEmpty() || path.equals(scope) || path.startsWith(scope + "/");
    }
}
