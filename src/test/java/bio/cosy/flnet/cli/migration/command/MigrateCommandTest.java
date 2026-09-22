package bio.cosy.flnet.cli.migration.command;

import bio.cosy.flnet.cli.base.deployment.DeploymentKind;
import bio.cosy.flnet.cli.base.deployment.FLNetClientDeployment;
import bio.cosy.flnet.cli.deploy.bo.BaseFLNetDeploymentBO;
import bio.cosy.flnet.cli.deploy.bo.FLNetDeploymentsBO;
import bio.cosy.flnet.cli.deploy.command.InstanceOptions;
import bio.cosy.flnet.cli.migration.MigrationCatalog;
import bio.cosy.flnet.cli.migration.MigrationCatalog.Task;
import bio.cosy.flnet.cli.migration.bo.FLNetMigrationBO;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.Prompter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static bio.cosy.flnet.cli.migration.bo.FLNetMigrationBOTest.*;
import static bio.cosy.flnet.cli.migration.MigrationFileHelper.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrateCommandTest {
    @Test
    void explainsChangesAndRequiresConfirmationBeforeWriting(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("docker-compose.yml"), "services: {}\n");
        Files.writeString(directory.resolve(".env"), "CUSTOM=preserved\n");
        Task add = op(Task.Type.FILE_ADD, "new.conf"); add.setValue("feature=true\n");
        MigrationCatalog catalog = new MigrationCatalog(); catalog.setMigrations(List.of(step(2, add)));
        FLNetMigrationBO bo = new FLNetMigrationBO() {
            { mapper = new ObjectMapper(); }
            @Override protected MigrationCatalog catalog() { return catalog; }
        };
        MigrateCommand command = new MigrateCommand(); command.kind = DeploymentKind.CLIENT; command.from = 1; command.migrations = bo;
        command.prompter = new Prompter(); command.interaction.noInput = true;
        command.deployments = new FLNetDeploymentsBO() {
            @Override public BaseFLNetDeploymentBO<FLNetClientDeployment> bo(DeploymentKind kind) {
                return new BaseFLNetDeploymentBO<>() {
                    @Override public DeploymentKind kind() { return DeploymentKind.CLIENT; }
                    @Override protected FLNetClientDeployment create() { return new FLNetClientDeployment(); }
                    @Override protected String baseProjectName() { return "test"; }
                    @Override public FLNetClientDeployment select(InstanceOptions options, Prompter prompter) {
                        FLNetClientDeployment result = create(); result.setDirectory(directory); return result;
                    }
                };
            }
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(output));
            command.dryRun = true;
            assertEquals(0, command.call());
            assertTrue(output.toString().contains("1 -> 2"));
            assertTrue(output.toString().contains("FILE_ADD new.conf"));
            assertFalse(Files.exists(directory.resolve(".flnet")));
            command.dryRun = false;
            assertTrue(assertThrows(CliException.class, command::call).getMessage().contains("--yes"));
            assertFalse(Files.exists(directory.resolve("new.conf")));
            command.interaction.yes = true;
            assertEquals(0, command.call());
            assertTrue(Files.exists(directory.resolve("new.conf")));
            assertTrue(Files.exists(directory.resolve(STATE)));
            output.reset();
            command.from = null;
            assertEquals(0, command.call());
            assertTrue(output.toString().contains("Already at the newest"));
        } finally {
            System.setOut(previous);
        }
    }
}
