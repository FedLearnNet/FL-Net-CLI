package bio.cosy.flnet.cli.migration;

import bio.cosy.flnet.cli.base.deployment.DeploymentKind;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;

@Data
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MigrationState {
    private DeploymentKind kind;
    private int revision;
}
