package bio.cosy.flnet.cli.helper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

public final class FilePermissionHelper {

    public static final String OWNER_ONLY = "rw-------";
    public static final String READABLE = "rw-r--r--";
    public static final String EXECUTABLE = "rwxr-xr-x";

    private FilePermissionHelper() {
    }

    public static void set(Path file, String permissions) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException _) {
            // no POSIX permissions on this file system
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot set permissions of " + file, e);
        }
    }
}
