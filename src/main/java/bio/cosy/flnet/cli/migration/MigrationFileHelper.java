package bio.cosy.flnet.cli.migration;

import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.FilePermissionHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MigrationFileHelper {
    public static final String STATE = ".flnet/migrations.json";

    private MigrationFileHelper() { }

    public static Path path(Path root, String name) {
        require(name != null && !name.isBlank() && !name.contains("\\"), "invalid file path");
        Path relative = Path.of(name);
        require(!relative.isAbsolute() && relative.normalize().equals(relative) && !relative.startsWith("..")
                && !name.equals("."), "path must stay inside the deployment: " + name);
        Path result = root;
        for (Path segment : relative) {
            result = result.resolve(segment);
            require(!Files.isSymbolicLink(result), "cannot edit a symbolic link: " + name);
        }
        return result;
    }

    public static byte[] read(Path file) throws IOException {
        return Files.exists(file) ? Files.readAllBytes(file) : null;
    }

    public static void write(Path file, byte[] bytes) throws IOException {
        if (bytes == null) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(file.getParent());
        boolean fresh = !Files.exists(file);
        if (fresh) Files.createFile(file);
        if (file.toString().contains("/.flnet/") || file.toString().endsWith(".env")) {
            FilePermissionHelper.set(file, FilePermissionHelper.OWNER_ONLY);
        } else if (fresh) {
            FilePermissionHelper.set(file, file.toString().endsWith(".sh") ? FilePermissionHelper.EXECUTABLE : FilePermissionHelper.READABLE);
        }
        Files.write(file, bytes);
    }

    public static void require(boolean condition, String reason) {
        if (!condition) throw CliException.usage("Migration conflict: " + reason + ".");
    }
}
