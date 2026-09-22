package bio.cosy.flnet.cli.migration.bo;

import bio.cosy.flnet.cli.base.deployment.DeploymentKind;
import bio.cosy.flnet.cli.migration.MigrationCatalog;
import bio.cosy.flnet.cli.migration.MigrationCatalog.Definition;
import bio.cosy.flnet.cli.migration.MigrationOperationHelper;
import bio.cosy.flnet.cli.migration.MigrationPlan;
import bio.cosy.flnet.cli.migration.MigrationState;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static bio.cosy.flnet.cli.migration.MigrationFileHelper.*;

@ApplicationScoped
public class FLNetMigrationBO {
    @Inject
    protected ObjectMapper mapper;

    protected MigrationCatalog catalog() throws IOException {
        try (var input = getClass().getResourceAsStream("/migrations/catalog.json")) {
            require(input != null, "migration catalog missing");
            return mapper.readerFor(MigrationCatalog.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(input);
        }
    }

    private List<Definition> definitions(DeploymentKind kind, MigrationCatalog catalog) {
        require(catalog != null && catalog.getBaseRevision() > 0 && catalog.getMigrations() != null, "invalid catalog");
        for (Definition migration : catalog.getMigrations()) {
            require(migration != null && migration.getKind() != null && migration.getDescription() != null
                    && !migration.getDescription().isBlank() && migration.getTasks() != null && !migration.getTasks().isEmpty(), "invalid migration");
        }
        var ordered = catalog.getMigrations().stream().filter(m -> m.getKind() == kind)
                .sorted(Comparator.comparingInt(Definition::getRevision)).toList();
        int revision = catalog.getBaseRevision();
        for (Definition migration : ordered) require(migration.getRevision() == ++revision, "missing or duplicate revision");
        return ordered;
    }

    public MigrationPlan plan(DeploymentKind kind, Path directory, Integer from) throws IOException {
        MigrationCatalog catalog = catalog();
        List<Definition> ordered = definitions(kind, catalog);
        Path root = directory.toRealPath();
        require(!Files.exists(path(root, ".flnet/migration-pending.json")), "unfinished older migration; inspect and repair it manually first");
        byte[] stateBytes = read(path(root, STATE));
        MigrationState state = stateBytes == null ? null : mapper.readValue(stateBytes, MigrationState.class);
        require(state != null || from != null, "revision unknown; pass --from <revision> after checking the deployment version");
        require(state == null || state.getKind() == kind, "deployment kind does not match its recorded state");
        int current = state == null ? from : state.getRevision();
        require(from == null || from == current, "--from disagrees with the recorded revision");
        int latest = ordered.isEmpty() ? catalog.getBaseRevision() : ordered.getLast().getRevision();
        require(current >= catalog.getBaseRevision() && current <= latest, "revision is not supported by this CLI");
        MigrationPlan plan = new MigrationPlan();
        plan.setKind(kind);
        plan.setDirectory(root);
        plan.setCurrentRevision(current);
        plan.setTargetRevision(latest);
        plan.setMigrations(ordered.stream().filter(m -> m.getRevision() > current).toList());
        for (Definition migration : plan.getMigrations()) {
            for (var task : migration.getTasks()) {
                require(task != null && task.getType() != null && task.getDescription() != null && !task.getDescription().isBlank(), "invalid task");
                Path file = path(root, task.getPath());
                require(!List.of(".flnet", ".git", "umls", "sapbert").contains(root.relativize(file).getName(0).toString())
                        && !task.getPath().endsWith(".pem") && !task.getPath().endsWith(".key"), "task must target a configuration file");
                if (!plan.getOriginals().containsKey(task.getPath())) {
                    plan.getOriginals().put(task.getPath(), read(file));
                    plan.getResults().put(task.getPath(), plan.getOriginals().get(task.getPath()));
                }
                plan.getResults().put(task.getPath(), MigrationOperationHelper.apply(task, plan.getResults().get(task.getPath())));
            }
        }
        if (!plan.getMigrations().isEmpty()) {
            plan.getOriginals().put(STATE, stateBytes);
            plan.getResults().put(STATE, stateBytes(kind, latest));
        }
        return plan;
    }

    public void migrateToNewest(MigrationPlan plan) throws IOException {
        for (var original : plan.getOriginals().entrySet()) {
            require(Arrays.equals(original.getValue(), read(path(plan.getDirectory(), original.getKey()))), "file changed since preview: " + original.getKey());
        }
        try {
            for (var result : plan.getResults().entrySet()) write(path(plan.getDirectory(), result.getKey()), result.getValue());
        } catch (IOException e) {
            throw new IOException("Migration stopped. Some files may already have changed; inspect them before retrying. No automatic recovery is available.", e);
        }
    }

    public void requireCompatibleForInit(DeploymentKind kind, Path directory) {
        try {
            require(!Files.exists(path(directory, ".flnet/migration-pending.json")), "unfinished older migration needs manual repair");
            byte[] bytes = read(path(directory, STATE));
            if (bytes == null) return;
            MigrationState state = mapper.readValue(bytes, MigrationState.class);
            require(state != null && state.getKind() == kind && state.getRevision() == latest(kind), "migrate first or use a compatible CLI before init");
        } catch (IOException e) { throw new UncheckedIOException("Cannot read deployment revision.", e); }
    }

    public void recordFreshDeployment(DeploymentKind kind, Path directory) {
        try { write(path(directory, STATE), stateBytes(kind, latest(kind))); }
        catch (IOException e) { throw new UncheckedIOException("Cannot record deployment revision.", e); }
    }

    private int latest(DeploymentKind kind) throws IOException {
        MigrationCatalog catalog = catalog();
        var definitions = definitions(kind, catalog);
        return definitions.isEmpty() ? catalog.getBaseRevision() : definitions.getLast().getRevision();
    }

    private byte[] stateBytes(DeploymentKind kind, int revision) throws IOException {
        MigrationState state = new MigrationState();
        state.setKind(kind);
        state.setRevision(revision);
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(state);
    }
}
