package bio.cosy.flnet.cli.deploy.bo;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.deploy.backup.BackupBO;
import bio.cosy.flnet.cli.deploy.backup.BaseBackupBO;
import bio.cosy.flnet.cli.deploy.backup.RestoreBO;
import bio.cosy.flnet.cli.deploy.upgrade.UpgradeState;
import bio.cosy.flnet.cli.deploy.upgrade.UpgradeStep;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.DirectoryHelper;
import bio.cosy.flnet.cli.helper.ReleaseVersion;
import bio.cosy.flnet.cli.network.bo.FLNetNetworkBO;
import tools.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Upgrades a deployment to the version of this flnet. The steps run apart, e.g. in containers of orch-api, so
 * updates/state.json carries what the last one did; an interrupted upgrade is rolled back first.
 */
@ApplicationScoped
public class UpgradeBO {
    public static final String STATE_FILE = "state.json";
    private static final String LOCK_FILE = "upgrade.lock";
    private static final String BACKUP_PREFIX = "upgrade_";
    private static final int KEEP_BACKUPS = 2;

    @Inject
    FLNetDeploymentsBO deployments;

    @Inject
    FLNetNetworkBO networks;

    @Inject
    BackupBO backupBO;

    @Inject
    RestoreBO restoreBO;

    @Inject
    ComposeBO composeBO;

    @Inject
    ObjectMapper objectMapper;

    /**
     * False if an apply failed and went back to the backup.
     */
    public boolean run(Path directory, UpgradeStep step, int waitSeconds) {
        BaseFLNetDeploymentBO<?> bo = deployments.bo(FLNetDeploymentsBO.kindOf(directory));
        Path updates = directory.resolve(BaseBackupBO.UPDATES_DIRECTORY);
        BaseBackupBO.createDirectory(updates);
        try (FileChannel channel = FileChannel.open(updates.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) {
                throw new CliException("Another upgrade runs for this deployment.");
            }
            UpgradeState state = load(updates);
            if (recover(bo, directory, state, waitSeconds) && step == UpgradeStep.ROLLBACK) {
                return true;
            }
            return switch (step) {
                case PREPARE -> prepare(bo, directory, state);
                case APPLY -> apply(bo, directory, state, waitSeconds);
                case ROLLBACK -> rollback(bo, directory, state, waitSeconds);
            };
        } catch (IOException e) {
            throw new CliException("Could not lock " + updates + ": " + e.getMessage(), CliException.ENVIRONMENT);
        }
    }

    public UpgradeState load(Path updates) {
        Path file = updates.resolve(STATE_FILE);
        if (!Files.isRegularFile(file)) {
            return new UpgradeState();
        }
        try {
            return objectMapper.readValue(file.toFile(), UpgradeState.class);
        } catch (JacksonException e) {
            throw new CliException("Could not read " + file + ": " + e.getMessage());
        }
    }

    // own version for a release, 'staging' for a develop build
    String targetVersion() {
        return networks.imageTag();
    }

    private <T extends BaseFLNetDeployableInstance> boolean prepare(BaseFLNetDeploymentBO<T> bo, Path directory, UpgradeState state) {
        T instance = instance(bo, directory);
        String target = targetVersion();
        Optional<ReleaseVersion> installed = ReleaseVersion.parse(instance.getImageTag());
        Optional<ReleaseVersion> wanted = ReleaseVersion.parse(target);
        if (installed.isPresent() && wanted.isPresent() && wanted.get().compareTo(installed.get()) <= 0) {
            throw CliException.usage(target + " is not newer than the installed " + instance.getImageTag() + ".");
        }
        state.setFlNetSoftwareVersion(target);
        state.setBackup(null);
        state.start(UpgradeStep.PREPARE, null);
        save(directory, state);
        // pulled in a copy while nothing changes; the temp directory is owner only, the copy holds the secrets
        Path copy = DirectoryHelper.createTempDirectory("flnet-upgrade-");
        try {
            DirectoryHelper.copy(directory, copy, List.of(BaseBackupBO.DIRECTORY, BaseBackupBO.UPDATES_DIRECTORY));
            T staged = bo.load(copy, instance.getName());
            staged.useImageTag(target);
            bo.write(staged, true, null);
            requireSuccess(composeBO.pull(staged), "Pulling the images");
        } catch (RuntimeException e) {
            fail(directory, state, "Prepare failed: " + e.getMessage());
            throw e;
        } finally {
            DirectoryHelper.delete(copy);
        }
        state.setDone(true);
        save(directory, state);
        return true;
    }

    private <T extends BaseFLNetDeployableInstance> boolean apply(BaseFLNetDeploymentBO<T> bo, Path directory, UpgradeState state,
                                                                int waitSeconds) {
        String target = targetVersion();
        if (!state.finished(UpgradeStep.PREPARE) || !target.equals(state.getFlNetSoftwareVersion())) {
            throw CliException.usage(target + " is not prepared, run 'flnet upgrade prepare' first.");
        }
        T instance = instance(bo, directory);
        state.start(UpgradeStep.APPLY, null);
        save(directory, state);
        String name = BACKUP_PREFIX + OffsetDateTime.now(ZoneOffset.UTC).format(BaseBackupBO.NAME_TIME);
        try {
            backupBO.create(instance, name, true);
        } catch (RuntimeException e) {
            // nothing changed yet, the prepared version can be applied again
            state.start(UpgradeStep.PREPARE, "Backup failed: " + e.getMessage());
            state.setDone(true);
            save(directory, state);
            throw e;
        }
        state.setBackup(name);
        save(directory, state);
        try {
            instance.useImageTag(target);
            bo.save(instance, true, null);
            up(bo, directory, waitSeconds);
        } catch (RuntimeException e) {
            ConsoleHelper.error("Upgrade to " + target + " failed: " + e.getMessage());
            restore(bo, directory, state, e.getMessage(), waitSeconds);
            return false;
        }
        state.setDone(true);
        save(directory, state);
        prune(backupBO.backupsDir(instance));
        ConsoleHelper.success("Upgraded to " + target + ".");
        return true;
    }

    private <T extends BaseFLNetDeployableInstance> boolean rollback(BaseFLNetDeploymentBO<T> bo, Path directory, UpgradeState state,
                                                                   int waitSeconds) {
        if (!state.finished(UpgradeStep.APPLY) || state.getBackup() == null) {
            throw CliException.usage("Nothing to roll back, the last step was " + state.getStep() + ".");
        }
        restore(bo, directory, state, "rollback requested", waitSeconds);
        return true;
    }

    // finishes an interrupted step (crash, reboot); true if it rolled back
    private <T extends BaseFLNetDeployableInstance> boolean recover(BaseFLNetDeploymentBO<T> bo, Path directory, UpgradeState state,
                                                                  int waitSeconds) {
        // an unfinished prepare changed nothing
        if (state.isDone() || state.getStep() == null || state.getStep() == UpgradeStep.PREPARE) {
            return false;
        }
        if (state.getStep() == UpgradeStep.APPLY && state.getBackup() == null) {
            // died during the backup, nothing changed; the next backup removes the incomplete one
            state.start(UpgradeStep.PREPARE, null);
            state.setDone(true);
            save(directory, state);
            return false;
        }
        ConsoleHelper.warn("The upgrade to " + state.getFlNetSoftwareVersion() + " did not finish, rolling back.");
        restore(bo, directory, state, "the upgrade was interrupted", waitSeconds);
        return true;
    }

    private <T extends BaseFLNetDeployableInstance> void restore(BaseFLNetDeploymentBO<T> bo, Path directory, UpgradeState state,
                                                               String reason, int waitSeconds) {
        state.start(UpgradeStep.ROLLBACK, reason);
        save(directory, state);
        try {
            restoreBO.restore(instance(bo, directory), state.getBackup());
            T restored = up(bo, directory, waitSeconds);
            state.setLastLogMessage("Rolled back to " + restored.getImageTag() + ": " + reason);
        } catch (RuntimeException e) {
            // stays not done, the next run tries again
            fail(directory, state, "Rollback failed after '" + reason + "': " + e.getMessage()
                    + ". The backup is backups/" + state.getBackup() + ".");
            throw e;
        }
        state.setDone(true);
        save(directory, state);
    }

    // from the files on disk now, until every service is healthy
    private <T extends BaseFLNetDeployableInstance> T up(BaseFLNetDeploymentBO<T> bo, Path directory, int waitSeconds) {
        T instance = instance(bo, directory);
        requireSuccess(composeBO.up(instance, false, waitSeconds), "Starting the services");
        return instance;
    }

    private static <T extends BaseFLNetDeployableInstance> T instance(BaseFLNetDeploymentBO<T> bo, Path directory) {
        T instance = bo.load(directory, directory.getFileName().toString());
        instance.requireInitialized();
        return instance;
    }

    private static void requireSuccess(int code, String what) {
        if (code != 0) {
            throw new CliException(what + " failed with exit code " + code + ".", code);
        }
    }

    private void fail(Path directory, UpgradeState state, String message) {
        state.setLastLogMessage(message);
        save(directory, state);
    }

    private void save(Path directory, UpgradeState state) {
        state.setUpdatedAt(new Date());
        DirectoryHelper.writeJson(objectMapper, directory.resolve(BaseBackupBO.UPDATES_DIRECTORY).resolve(STATE_FILE), state);
    }

    // newest first by name; other backups are never touched
    private static void prune(Path backups) {
        try (Stream<Path> entries = Files.list(backups)) {
            entries.filter(path -> path.getFileName().toString().startsWith(BACKUP_PREFIX))
                    .sorted(Comparator.reverseOrder())
                    .skip(KEEP_BACKUPS)
                    .forEach(DirectoryHelper::delete);
        } catch (IOException e) {
            ConsoleHelper.warn("Could not remove old backups in " + backups + ": " + e.getMessage());
        }
    }
}
