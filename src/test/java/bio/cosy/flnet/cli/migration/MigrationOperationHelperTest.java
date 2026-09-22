package bio.cosy.flnet.cli.migration;

import bio.cosy.flnet.cli.migration.MigrationCatalog.Task;
import bio.cosy.flnet.cli.helper.CliException;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static bio.cosy.flnet.cli.migration.bo.FLNetMigrationBOTest.op;
import static org.junit.jupiter.api.Assertions.*;

class MigrationOperationHelperTest {
    @Test
    void preservesCustomDefaultsQuotedValuesAndCrLf() {
        Task add = op(Task.Type.ENV_ADD, ".env"); add.setKey("CUSTOM"); add.setValue("default");
        String text = "# comment\r\nCUSTOM=\"user value\"\r\nKEEP=yes\r\n";
        assertEquals(text, apply(add, text));
        Task rename = op(Task.Type.ENV_RENAME, ".env"); rename.setKey("CUSTOM"); rename.setNewKey("RENAMED");
        assertEquals(text.replace("CUSTOM=", "RENAMED="), apply(rename, text));
        Task remove = op(Task.Type.ENV_REMOVE, ".env"); remove.setKey("CUSTOM"); remove.setExpectedValue("\"user value\"");
        assertEquals("# comment\r\nKEEP=yes\r\n", apply(remove, text));
    }

    @Test
    void rejectsDuplicateKeysRenameCollisionsAndAmbiguousText() {
        Task rename = op(Task.Type.ENV_RENAME, ".env"); rename.setKey("OLD"); rename.setNewKey("NEW");
        assertThrows(CliException.class, () -> apply(rename, "OLD=1\nOLD=2\n"));
        assertThrows(CliException.class, () -> apply(rename, "OLD=1\nNEW=2\n"));
        assertThrows(CliException.class, () -> apply(rename, "OLD='multiline\ntext'\n"));
        Task replace = op(Task.Type.TEXT_REPLACE, "docker-compose.yml");
        replace.setExpectedValue("image: old"); replace.setValue("image: new");
        assertThrows(CliException.class, () -> apply(replace, "image: old\nimage: old\n"));
    }

    private String apply(Task operation, String text) {
        return new String(MigrationOperationHelper.apply(operation, text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }
}
