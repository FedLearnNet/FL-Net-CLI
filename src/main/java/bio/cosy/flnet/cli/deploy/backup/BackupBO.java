package bio.cosy.flnet.cli.deploy.backup;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.DirectoryHelper;
import bio.cosy.flnet.cli.helper.ProcessHelper;
import bio.cosy.flnet.cli.helper.ProcessResult;
import tools.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@ApplicationScoped
public class BackupBO extends BaseBackupBO {

    /**
     * The databases, with all also the deployment directory (configuration and secrets).
     */
    public DeploymentBackup create(BaseFLNetDeployableInstance instance, String name, boolean all) {
        instance.requireInitialized();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String backupName = name == null ? "backup_" + now.format(NAME_TIME) : requireValidName(name);
        removeIncomplete(instance);
        Path target = backupsDir(instance).resolve(backupName);
        if (Files.exists(target)) {
            throw CliException.usage("Backup '" + backupName + "' already exists in " + backupsDir(instance) + ".");
        }
        DeploymentBackup backup = new DeploymentBackup();
        backup.setName(backupName);
        backup.setCreatedAt(Date.from(now.toInstant()));
        backup.setVersion(instance.getImageTag());
        backup.setBackUpAll(all);
        try {
            createDirectory(target);
            save(target, backup);
            if (all) {
                DirectoryHelper.copy(instance.getDirectory(), target.resolve(DeploymentBackup.FILES), List.of(DIRECTORY, UPDATES_DIRECTORY));
            }
            Path dumps = target.resolve(DeploymentBackup.DATABASES);
            createDirectory(dumps);
            List<DatabaseDump> databases = findDatabases(instance);
            for (DatabaseDump database : databases) {
                ConsoleHelper.info("Dumping " + database.getDatabase() + " of " + database.getService());
                dump(instance, database, dumps.resolve(database.getFile()));
            }
            backup.setDatabases(databases);
            backup.setComplete(true);
            save(target, backup);
        } catch (RuntimeException e) {
            fail(target, backup, e);
            throw e;
        }
        return backup;
    }

    void removeIncomplete(BaseFLNetDeployableInstance instance) {
        Path backups = backupsDir(instance);
        if (!Files.isDirectory(backups)) {
            return;
        }
        try (Stream<Path> entries = Files.list(backups)) {
            for (Path entry : entries.filter(Files::isDirectory).toList()) {
                // folders without manifest are not ours, e.g. those of create-backup.sh
                if (Files.isRegularFile(entry.resolve(DeploymentBackup.MANIFEST)) && !load(entry).isComplete()) {
                    ConsoleHelper.warn("Removing the incomplete backup " + entry.getFileName());
                    DirectoryHelper.delete(entry);
                }
            }
        } catch (IOException e) {
            throw new CliException("Could not read " + backups + ": " + e.getMessage(), CliException.ENVIRONMENT);
        }
    }

    List<DatabaseDump> findDatabases(BaseFLNetDeployableInstance instance) {
        String config = ProcessHelper.capture(instance.getDirectory(), List.of("docker", "compose", "config", "--format", "json"));
        try {
            return databasesOf(objectMapper.readTree(config));
        } catch (JacksonException e) {
            throw new CliException("Could not read the output of 'docker compose config': " + e.getMessage());
        }
    }

    // Postgres services, found by POSTGRES_DB and POSTGRES_USER
    static List<DatabaseDump> databasesOf(JsonNode config) {
        List<DatabaseDump> databases = new ArrayList<>();
        for (Map.Entry<String, JsonNode> service : config.path("services").properties()) {
            JsonNode environment = service.getValue().path("environment");
            String database = environmentValue(environment, "POSTGRES_DB");
            String user = environmentValue(environment, "POSTGRES_USER");
            if (database != null && user != null) {
                databases.add(new DatabaseDump(service.getKey(), user, database, service.getKey() + ".sql"));
            }
        }
        return databases;
    }

    // a map, or a list of KEY=value
    private static String environmentValue(JsonNode environment, String key) {
        if (environment.isObject()) {
            String value = environment.path(key).asString("");
            return value.isEmpty() ? null : value;
        }
        for (JsonNode entry : environment) {
            String text = entry.asString();
            if (text.startsWith(key + "=") && text.length() > key.length() + 1) {
                return text.substring(key.length() + 1);
            }
        }
        return null;
    }

    private void dump(BaseFLNetDeployableInstance instance, DatabaseDump database, Path file) {
        ProcessResult result = ProcessHelper.runToFile(instance.getDirectory(), List.of("docker", "compose", "exec", "-T",
                database.getService(), "pg_dump", "-U", database.getUser(), "-d", database.getDatabase()), file);
        if (result.failed()) {
            throw new CliException("Dumping " + database.getDatabase() + " of " + database.getService() + " failed: "
                    + result.reason(), result.getCode());
        }
    }

    // only the manifest with the reason stays, for the admin page
    private void fail(Path target, DeploymentBackup backup, RuntimeException cause) {
        DirectoryHelper.delete(target.resolve(DeploymentBackup.FILES));
        DirectoryHelper.delete(target.resolve(DeploymentBackup.DATABASES));
        backup.setMessage(cause.getMessage());
        try {
            save(target, backup);
        } catch (RuntimeException e) {
            DirectoryHelper.delete(target);
        }
    }

    private void save(Path backup, DeploymentBackup content) {
        DirectoryHelper.writeJson(objectMapper, backup.resolve(DeploymentBackup.MANIFEST), content);
    }
}
