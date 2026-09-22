package bio.cosy.flnet.cli.migration;

import bio.cosy.flnet.cli.base.deployment.DeploymentKind;
import bio.cosy.flnet.cli.migration.MigrationCatalog.Definition;
import lombok.Data;
import lombok.ToString;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class MigrationPlan {
    private DeploymentKind kind;
    private Path directory;
    private int currentRevision;
    private int targetRevision;
    private List<Definition> migrations = new ArrayList<>();
    @ToString.Exclude
    private Map<String, byte[]> originals = new LinkedHashMap<>();
    @ToString.Exclude
    private Map<String, byte[]> results = new LinkedHashMap<>();
}
