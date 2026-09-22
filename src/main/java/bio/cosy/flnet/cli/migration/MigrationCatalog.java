package bio.cosy.flnet.cli.migration;

import bio.cosy.flnet.cli.base.deployment.DeploymentKind;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Data
@RegisterForReflection
public class MigrationCatalog {
    private int baseRevision = 1;
    private List<Definition> migrations = new ArrayList<>();

    @Data
    public static class Definition {
        private DeploymentKind kind;
        private int revision;
        private String description;
        private List<Task> tasks = new ArrayList<>();
    }

    @Data
    public static class Task {
        public enum Type { FILE_ADD, FILE_REPLACE, FILE_REMOVE, TEXT_REPLACE, ENV_ADD, ENV_SET, ENV_RENAME, ENV_REMOVE }
        private Type type;
        private String path;
        private String description;
        private String key;
        private String newKey;
        @ToString.Exclude
        private String value;
        @ToString.Exclude
        private String expectedValue;
    }
}
