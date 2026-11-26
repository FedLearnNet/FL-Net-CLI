package bio.cosy.flnet.cli.deploy.upgrade;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
public class UpgradeState {
    private UpgradeStep step;
    private boolean done;
    private String flNetSoftwareVersion;
    private String backup;
    private String lastLogMessage;
    private Date updatedAt;

    public void start(UpgradeStep step, String message) {
        this.step = step;
        this.done = false;
        this.lastLogMessage = message;
    }

    public boolean finished(UpgradeStep step) {
        return this.step == step && done;
    }
}
