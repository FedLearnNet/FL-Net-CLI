package bio.cosy.flnet.cli.helper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessHelperTest {

    @TempDir
    Path directory;

    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    @Test
    void preservesExitCodesInBothOutputModes() {
        List<String> command = List.of(JAVA, "--flnet-invalid-option");
        assertEquals(1, ProcessHelper.runInteractive(directory, command));
        assertEquals(1, ProcessHelper.runToFile(directory, command, directory.resolve("output")));
        assertEquals(0, ProcessHelper.runInteractive(null, List.of(JAVA, "--version")));
    }

    @Test
    void replacesOutputWithOwnerOnlyFile() throws IOException {
        Path output = Files.writeString(directory.resolve("output"), "old contents");
        if (Files.getFileAttributeView(output, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(output, PosixFilePermissions.fromString(FilePermissionHelper.READABLE));
        }
        assertEquals(0, ProcessHelper.runToFile(directory, List.of(JAVA, "--version"), output));
        assertFalse(Files.readString(output).isBlank());
        assertFalse(Files.readString(output).contains("old contents"));
        if (Files.getFileAttributeView(output, PosixFileAttributeView.class) != null) {
            assertEquals(FilePermissionHelper.OWNER_ONLY, PosixFilePermissions.toString(Files.getPosixFilePermissions(output)));
        }
    }

    @Test
    void missingProgramUsesTheSameEnvironmentError() {
        List<String> command = List.of(directory.resolve("missing-program").toString());
        CliException interactive = assertThrows(CliException.class, () -> ProcessHelper.runInteractive(directory, command));
        CliException redirected = assertThrows(CliException.class,
                () -> ProcessHelper.runToFile(directory, command, directory.resolve("output")));
        assertEquals(CliException.ENVIRONMENT, interactive.exitCode());
        assertEquals(interactive.exitCode(), redirected.exitCode());
        assertEquals(interactive.getMessage(), redirected.getMessage());
        assertTrue(interactive.getMessage().contains("flnet doctor"));
    }
}
