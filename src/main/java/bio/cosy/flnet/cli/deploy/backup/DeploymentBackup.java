package bio.cosy.flnet.cli.deploy.backup;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@NoArgsConstructor
public class DeploymentBackup {
    public static final String MANIFEST = "backup.json";
    public static final String FILES = "files";
    public static final String DATABASES = "db";

    private String name;
    private Date createdAt;
    // IMAGE_TAG of the deployment
    private String version;
    private List<DatabaseDump> databases = new ArrayList<>();
    private boolean backUpAll;
    // set once everything is written, an incomplete backup is never restored
    private boolean complete;
    private String message;
}
