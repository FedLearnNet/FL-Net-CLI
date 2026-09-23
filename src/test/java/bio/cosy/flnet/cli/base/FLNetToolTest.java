package bio.cosy.flnet.cli.base;

import bio.cosy.flnet.cli.base.tool.FLNetTool;
import bio.cosy.flnet.cli.base.tool.FLNetToolField;
import bio.cosy.flnet.cli.base.tool.ToolType;
import bio.cosy.flnet.cli.helper.WebAddress;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

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
    void toolFieldDerivesThePythonTypeFromItsKind() {
        assertEquals("int", FLNetToolField.hyper("n", "INTEGER", "1", "").getPythonType());
        assertEquals("float", FLNetToolField.hyper("n", "FLOAT", "1.5", "").getPythonType());
        assertEquals("bool", FLNetToolField.hyper("n", "BOOLEAN", "true", "").getPythonType());
        assertEquals("str", FLNetToolField.hyper("n", "STRING", "x", "").getPythonType());
        assertEquals("Any", FLNetToolField.io("input", "CSV", "", true).getPythonType());
    }

    @Test
    void toolTypeCliNamesAndCapabilitiesAreConsistent() {
        assertEquals("pre-processing", ToolType.PRE_PROCESSING.cliName());
        assertEquals("data-transformation", ToolType.DATA_TRANSFORMATION.cliName());
        assertTrue(ToolType.cliNames().contains("analysis"));

        assertTrue(ToolType.ANALYSIS.supportsFederated());
        assertFalse(ToolType.EXPORT.supportsFederated());

        assertFalse(ToolType.DATA_TRANSFORMATION.hasConfigModule());
        for (ToolType type : ToolType.values()) {
            if (type != ToolType.DATA_TRANSFORMATION) {
                assertTrue(type.hasConfigModule(), type.name());
            }
        }
    }

    @Test
    void toolTypeSwapsInFederatedHyperparamsAndOutputs() {
        assertEquals(List.of("epochs"), ToolType.ANALYSIS.hyperparams(false).stream().map(FLNetToolField::getName).toList());
        List<FLNetToolField> federatedHyperparams = ToolType.ANALYSIS.hyperparams(true);
        assertEquals(1, federatedHyperparams.size());
        assertEquals("communication_id", federatedHyperparams.get(0).getName());

        assertFalse(ToolType.ANALYSIS.outputs(false).isEmpty());
        List<FLNetToolField> federatedOutputs = ToolType.ANALYSIS.outputs(true);
        assertEquals(1, federatedOutputs.size());
        assertEquals("Federated mean of the numeric input columns.", federatedOutputs.get(0).getDescription());

        assertEquals(ToolType.EXTRACTOR.inputs(), List.of());
        assertFalse(ToolType.EXTRACTOR.hyperparams(false).isEmpty());
    }

    @Test
    void validatesAndDerivesTemplateValues() {
        FLNetTool tool = new FLNetTool();
        tool.setName("Fed Mean");
        tool.setDescription("Say \"hi\"");
        tool.setSourceUrl("https://example.org/repo");
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
        assertEquals("https://fl.example.org:8443", tool.getPlatformUrl());
        assertEquals("\"Fed Mean\"", tool.getNameYaml());
        assertEquals("\"Say \\\"hi\\\"\"", tool.getDescriptionYaml());
        assertEquals("\"https://example.org/repo\"", tool.getSourceUrlYaml());
        assertEquals("ANALYSIS", tool.getType());
        assertTrue(tool.hasConfigModule());
        assertTrue(tool.hasInputs());
        assertEquals(List.of("epochs"), tool.getHyperparams().stream().map(FLNetToolField::getName).toList());
        assertEquals(List.of("output"), tool.getOutputs().stream().map(FLNetToolField::getName).toList());

        tool.setFederated(true);
        assertEquals(List.of("communication_id"), tool.getHyperparams().stream().map(FLNetToolField::getName).toList());
        assertEquals(List.of("output"), tool.getOutputs().stream().map(FLNetToolField::getName).toList());
        assertEquals("Federated mean of the numeric input columns.", tool.getOutputs().get(0).getDescription());

        tool.setToolType(ToolType.EXPORT);
        tool.setAppId(" ");
        assertEquals(2, tool.problems(VALIDATOR).size(), tool.problems(VALIDATOR).toString());
    }
}
