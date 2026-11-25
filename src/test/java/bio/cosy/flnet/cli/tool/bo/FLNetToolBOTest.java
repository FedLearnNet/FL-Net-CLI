package bio.cosy.flnet.cli.tool.bo;

import bio.cosy.flnet.cli.base.tool.CommunicationEngine;
import bio.cosy.flnet.cli.base.tool.FLNetTool;
import bio.cosy.flnet.cli.base.tool.ToolType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class FLNetToolBOTest {

    @Inject
    FLNetToolBO toolBO;

    private FLNetTool flowerTool(Path dir) {
        FLNetTool tool = toolBO.create("Fed Flower");
        tool.setToolType(ToolType.ANALYSIS);
        tool.setFederated(true);
        tool.setCommunicationEngine(CommunicationEngine.FLOWER);
        tool.setDescription("A Flower tool");
        tool.setDirectory(dir);
        return tool;
    }

    @Test
    void generatesAFlowerAppBundleInsteadOfAppPy(@TempDir Path dir) {
        FLNetTool tool = flowerTool(dir);

        toolBO.generate(tool, false);

        assertFalse(Files.exists(dir.resolve("app.py")), "a Flower tool has no app.py");
        assertFalse(Files.exists(dir.resolve("aggregator.py")), "a Flower tool has no aggregator.py");
        assertFalse(Files.exists(dir.resolve("config.py")), "a Flower tool has no config.py");
        assertFalse(Files.exists(dir.resolve("data")), "a Flower tool has no sample-input data dir");

        String pkg = tool.getPythonPackage();
        assertTrue(Files.exists(dir.resolve("flower_app/pyproject.toml")));
        assertTrue(Files.exists(dir.resolve("flower_app/" + pkg + "/__init__.py")));
        assertTrue(Files.exists(dir.resolve("flower_app/" + pkg + "/server_app.py")));
        assertTrue(Files.exists(dir.resolve("flower_app/" + pkg + "/client_app.py")));
        assertTrue(Files.exists(dir.resolve("flower_app/" + pkg + "/task.py")));
    }

    @Test
    void flowerPyprojectPointsAtTheGeneratedServerAndClientApps(@TempDir Path dir) throws Exception {
        FLNetTool tool = flowerTool(dir);
        toolBO.generate(tool, false);

        String pyproject = Files.readString(dir.resolve("flower_app/pyproject.toml"));
        String pkg = tool.getPythonPackage();
        assertTrue(pyproject.contains("serverapp = \"" + pkg + ".server_app:app\""));
        assertTrue(pyproject.contains("clientapp = \"" + pkg + ".client_app:app\""));
    }

    @Test
    void generatedPythonFilesHaveNoLeftoverQuteEscapeArtifacts(@TempDir Path dir) throws Exception {
        FLNetTool tool = flowerTool(dir);
        toolBO.generate(tool, false);

        String pkg = tool.getPythonPackage();
        String clientApp = Files.readString(dir.resolve("flower_app/" + pkg + "/client_app.py"));
        assertFalse(clientApp.contains("\\{"), clientApp);
        assertFalse(clientApp.contains("\\}"), clientApp);
        assertTrue(clientApp.contains("content = RecordDict({"));
        assertTrue(clientApp.contains("\"metrics\": MetricRecord({\"num-examples\": 1}),"));
        assertTrue(clientApp.contains("content = RecordDict({\"metrics\": MetricRecord({\"num-examples\": 1})})"));
    }

    @Test
    void mainPyRegistersTheFlowerAppBundle(@TempDir Path dir) throws Exception {
        FLNetTool tool = flowerTool(dir);
        toolBO.generate(tool, false);

        String mainPy = Files.readString(dir.resolve("main.py"));
        assertTrue(mainPy.contains("from pyfedappwrap.learning.federated.flower_app import FlowerApp"));
        assertTrue(mainPy.contains("engine.register_flower(FlowerApp(app_dir=\"flower_app\", model_file=\"model.npz\"))"));
        assertFalse(mainPy.contains("register_federated"), "a Flower tool must not also register FL-Net's own federated path");
    }

    @Test
    void theServerAppSavesTheModelUnderTheFileNameMainPyDeclares(@TempDir Path dir) throws Exception {
        FLNetTool tool = flowerTool(dir);
        toolBO.generate(tool, false);
        String pkg = tool.getPythonPackage();

        String serverApp = Files.readString(dir.resolve("flower_app/" + pkg + "/server_app.py"));
        assertTrue(serverApp.contains("model_dir() / \"model.npz\""),
                "the engine only reports the run finished once main.py's model_file was saved");
    }

    @Test
    void appYmlTagsTheCommunicationEngineAndHasNoFlowerSpecificSchema(@TempDir Path dir) throws Exception {
        FLNetTool tool = flowerTool(dir);
        toolBO.generate(tool, false);

        String appYml = Files.readString(dir.resolve("app.yml"));
        assertTrue(appYml.contains("communicationEngine: FLOWER"));
        assertTrue(appYml.contains("hyperparams: []"));
        assertTrue(appYml.contains("input: []"));
        assertTrue(appYml.contains("output: []"));
    }

    @Test
    void requirementsTxtAddsFlowerDependenciesOnlyForFlowerTools(@TempDir Path dir) throws Exception {
        FLNetTool flower = flowerTool(dir.resolve("flower"));
        toolBO.generate(flower, false);
        String flowerRequirements = Files.readString(dir.resolve("flower/requirements.txt"));
        assertTrue(flowerRequirements.contains("flwr>="));

        FLNetTool flNet = toolBO.create("Fed Mean");
        flNet.setToolType(ToolType.ANALYSIS);
        flNet.setFederated(true);
        flNet.setDescription("An FL-Net tool");
        flNet.setDirectory(dir.resolve("fl-net"));
        toolBO.generate(flNet, false);
        String flNetRequirements = Files.readString(dir.resolve("fl-net/requirements.txt"));
        assertFalse(flNetRequirements.contains("flwr"));
    }
}
