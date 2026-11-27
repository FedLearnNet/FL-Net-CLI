package bio.cosy.flnet.cli.deploy.backup;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.FilePermissionHelper;
import tools.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

public abstract class BaseBackupBO {
    public static final String DIRECTORY = "backups";
    // state of the updater, a restore must not put back an old one
    public static final String UPDATES_DIRECTORY = "updates";
    public static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    @Inject
    ObjectMapper objectMapper;

    public Path backupsDir(BaseFLNetDeployableInstance instance) {
        return instance.resolve(DIRECTORY);
    }

    public DeploymentBackup load(Path backup) {
        Path manifest = backup.resolve(DeploymentBackup.MANIFEST);
        if (!Files.isRegularFile(manifest)) {
            throw CliException.usage("No backup in " + backup + ".");
        }
        try {
            return objectMapper.readValue(manifest.toFile(), DeploymentBackup.class);
        } catch (JacksonException e) {
            throw new CliException("Could not read " + manifest + ": " + e.getMessage());
        }
    }

    // owner only, dumps and env/ hold secrets
    public static void createDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            FilePermissionHelper.set(directory, FilePermissionHelper.OWNER_ONLY_DIRECTORY);
        } catch (IOException e) {
            throw new CliException("Could not create " + directory + ": " + e.getMessage(), CliException.ENVIRONMENT);
        }
    }

    static String requireValidName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw CliException.usage("Invalid backup name '" + name + "'. Use letters, digits, '.', '_' and '-'.");
        }
        return name;
    }
}
