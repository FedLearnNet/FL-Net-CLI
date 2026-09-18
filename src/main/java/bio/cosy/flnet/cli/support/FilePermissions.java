package bio.cosy.flnet.cli.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** POSIX file permissions; a no-op on Windows, where files inherit the directory ACL. */
public final class FilePermissions {

    /** Secrets and deployment settings. */
    public static final String OWNER_ONLY = "rw-------";
    /** Files other container users must read (e.g. certificates mounted into nginx). */
    public static final String READABLE = "rw-r--r--";
    public static final String EXECUTABLE = "rwxr-xr-x";

    private FilePermissions() {
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
