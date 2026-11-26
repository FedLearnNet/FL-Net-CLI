package bio.cosy.flnet.cli.helper;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class DirectoryHelper {

    private DirectoryHelper() {
    }

    /**
     * Copies a directory tree with its permissions; top level entries named in skip are left out.
     */
    public static void copy(Path source, Path target, List<String> skip) {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                if (relative.getNameCount() > 0 && skip.contains(relative.getName(0).toString())) {
                    continue;
                }
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES,
                            LinkOption.NOFOLLOW_LINKS);
                }
            }
        } catch (IOException e) {
            throw new CliException("Could not copy " + source + " to " + target + ": " + e.getMessage(), CliException.ENVIRONMENT);
        }
    }

    public static void delete(Path directory) {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new CliException("Could not delete " + directory + ": " + e.getMessage(), CliException.ENVIRONMENT);
        }
    }

    // atomic, a crash never leaves half a file
    public static void writeJson(ObjectMapper objectMapper, Path file, Object content) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), content);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new CliException("Could not write " + file + ": " + e.getMessage(), CliException.ENVIRONMENT);
        }
    }

    public static Path createTempDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new CliException("Could not create a temporary directory: " + e.getMessage(), CliException.ENVIRONMENT);
        }
    }
}
