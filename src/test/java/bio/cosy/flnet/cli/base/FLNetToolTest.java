package bio.cosy.flnet.cli.base;

import bio.cosy.flnet.cli.support.WebAddress;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static bio.cosy.flnet.cli.base.TestValidator.VALIDATOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FLNetToolTest {

    @Test
    void derivesPythonClassNamesAndSlugs() {
        assertEquals("RandomForest", FLNetTool.className("random forest"));
        assertEquals("FedMeanV2", FLNetTool.className("Fed Mean: v2"));
        assertEquals("MyTool", FLNetTool.className("my_tool"));
        assertEquals("App3dViewer", FLNetTool.className("3d viewer"));
        assertEquals("MyApp", FLNetTool.className("!!!"));
        assertEquals("random-forest", FLNetTool.slug("Random Forest"));
        assertEquals("fed-mean-v2", FLNetTool.slug("  Fed Mean: v2 "));
        assertEquals("my-tool", FLNetTool.slug("???"));
        assertEquals("\"a: \\\"b\\\"\"", FLNetTool.yamlString("a: \"b\""));
    }

    @Test
    void parsesToolTypesAndFields() {
        assertEquals(ToolType.PRE_PROCESSING, ToolType.fromCli("pre-processing"));
        assertEquals(ToolType.DATA_TRANSFORMATION, ToolType.fromCli("DATA_TRANSFORMATION"));
        assertThrows(IllegalArgumentException.class, () -> ToolType.fromCli("nope"));
        assertEquals("10", FLNetToolField.hyper("epochs", "INTEGER", "10", "").getPythonDefault());
        assertEquals("True", FLNetToolField.hyper("drop", "BOOLEAN", "true", "").getPythonDefault());
        assertEquals("\"say \\\"hi\\\"\"", FLNetToolField.hyper("s", "STRING", "say \"hi\"", "").getPythonDefault());
        assertEquals("None", FLNetToolField.io("input", "CSV", "", true).getPythonDefault());
    }

    @Test
    void validatesAndDerivesTemplateValues() {
        FLNetTool tool = new FLNetTool();
        tool.setName("Fed Mean");
        tool.setDirectory(Path.of("fed"));
        tool.setAppId("1");
        tool.setSdkVersion("0.7.12");
        tool.setBaseImage("base");
        tool.setPlatformAddress(WebAddress.parse("https://fl.example.org:8443"));
        tool.setKeycloakRealmPath("/auth/realms/FLNet-Platform");
        assertTrue(tool.problems(VALIDATOR).isEmpty(), tool.problems(VALIDATOR).toString());
        assertFalse(tool.isLinkedToPlatform("1"));
        assertEquals("wss://fl.example.org:8443", tool.getWsUrl());
        assertEquals("https://fl.example.org:8443/auth/realms/FLNet-Platform", tool.getKeycloakUrl());

        tool.setToolType(ToolType.EXPORT);
        tool.setFederated(true);
        tool.setAppId(" ");
        assertEquals(2, tool.problems(VALIDATOR).size(), tool.problems(VALIDATOR).toString());
    }
}
