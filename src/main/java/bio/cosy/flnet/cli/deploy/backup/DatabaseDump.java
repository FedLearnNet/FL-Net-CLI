package bio.cosy.flnet.cli.deploy.backup;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseDump {
    private String service;
    private String user;
    private String database;
    // in the db folder of the backup
    private String file;
}
