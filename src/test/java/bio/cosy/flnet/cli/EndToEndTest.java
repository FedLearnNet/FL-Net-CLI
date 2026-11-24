package bio.cosy.flnet.cli;

import bio.cosy.flnet.cli.helper.ProcessHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs a real platform and two clients, each a DinD container of {@code dind/} that sets itself up with flnet,
 * and checks the important features through their REST APIs. Needs docker that runs privileged containers and
 * access to the image registries (private ghcr.io packages need {@code docker login ghcr.io} and
 * {@code -De2e.docker-config}); takes well over an hour, most of it the platform building the tools.
 *
 * <pre>./mvnw test -Pe2e</pre>
 * <p>
 * The methods share the deployment and run in order; a scenario is skipped when the deployment did not come up,
 * and {@link #cleanUp} removes it after the last one.
 * <ol>
 *     <li>Health: the platform and both clients up, their health endpoints UP. The platform builds only the tools
 *     of {@link #BUILD_ONLY_TOOLS} (as {@code flnet.tool-imports.build-only} does in dev) and {@link #E2E_TOOLS};
 *     the test user {@value #PLATFORM_USER} owns them like every imported tool.</li>
 *     <li>Federation: both clients connected to the platform.</li>
 *     <li>ETL: each client imported half of the US-130 test data through the connector of
 *     {@code FL-Net-Paper/dind/us-130-clinics.json} into a cohort of the platform's US-130 schema.</li>
 *     <li>Self-test: every built tool starts as a {@code SELF_TEST} test engine on generated input or its own
 *     {@code get_test_data()}; its exit code settles the run, a failing tool is reported, as by the build pipeline.</li>
 *     <li>App export: the first client exports its cohort through the export app {@code patient-export-umap}, which
 *     gets the cohort's data entries.</li>
 *     <li>ETL with apps: the second client imports its clinic again with the app transformers that combine and split
 *     the diagnoses, the cohort stays the same.</li>
 *     <li>Single app analysis: {@code logistic-regression} on {@code e2e/iris.csv} (the content of
 *     {@code Documentation/static/sample-data/iris.csv}) in the file store of a data analysis, with output files.</li>
 *     <li>Workflow analysis: the bundled workflow {@value #WORKFLOW} on the same file, every step finished.</li>
 *     <li>Experiment: {@code logistic-regression} trained on iris by an {@code ENGINE} test engine with a grid of two
 *     values, two trials, the best one selected as the search ranks it; its model version published, its image built
 *     by a model pipeline and used for a prediction in a data analysis.</li>
 *     <li>Test engine protocol: a {@code SINGLE_RUN} engine on the iris file of the file store, then an
 *     {@code ENGINE} serving a test run and an experiment, stopped at the end.</li>
 *     <li>Training on the platform: a project's workflow trained on the project's iris file.</li>
 *     <li>Query and statistics: a federated query answered by both clients, then their data statistics.</li>
 *     <li>Federated training: {@value #US130} trained across both clients with a model version; each client's
 *     run of it and the data it exported for it; the training metrics both clients report on request.</li>
 *     <li>Project test: the central iris project of the platform training tested on its file.</li>
 *     <li>Federated test run: an {@code ENGINE} test engine runs a federated test run of the federated project, on the
 *     query of the imported clinics, with the platform's dockerized relay and controller; the platform puts dummy data
 *     of the project's export into the engine's volume.</li>
 * </ol>
 * Every execution purpose, mode and location runs, see {@link #assertExecution}:
 * <table>
 *     <tr><th>Purpose</th><th>Mode</th><th>Location</th><th>Scenarios</th></tr>
 *     <tr><td>ANALYSIS</td><td>SINGLE</td><td>PLATFORM</td><td>single app, workflow, prediction</td></tr>
 *     <tr><td>TRAINING</td><td>SINGLE</td><td>PLATFORM</td><td>training on the platform, project test</td></tr>
 *     <tr><td>TRAINING</td><td>FEDERATED</td><td>LOCAL</td><td>federated training, on the platform and each client</td></tr>
 *     <tr><td>PROCESSING</td><td>SINGLE</td><td>LOCAL</td><td>app export; ETL with apps, checked through its connector run</td></tr>
 *     <tr><td>TEST</td><td>SINGLE</td><td>PLATFORM</td><td>experiments, test runs</td></tr>
 *     <tr><td>TEST</td><td>FEDERATED</td><td>PLATFORM</td><td>federated test run</td></tr>
 *     <tr><td>TEST_HOSTING</td><td>SINGLE</td><td>PLATFORM</td><td>self-tests, single run and engines</td></tr>
 * </table>
 * Every run is checked by {@link #assertFinished}, the scenarios add their own checks. The containers share a
 * docker network on which the platform is {@value #PLATFORM_HOST}; each has its own docker daemon with the
 * address pools of {@code FL-Net-Paper/dind/daemon.json}.
 * <p>
 * Properties:
 * <ul>
 *     <li>{@code e2e.image-tag}: the FL-Net service images under test (default {@value #DEFAULT_IMAGE_TAG}, the
 *     develop builds; {@code latest} for main)</li>
 *     <li>{@code e2e.images}: single images instead, e.g. those of a pull request,
 *     {@code global-learning-api=ghcr.io/...:pr-12,orch-api=...}, by compose service</li>
 *     <li>{@code e2e.local-images}: images on this machine instead, e.g. built from a local branch, by compose
 *     service; the DinD containers load them and never pull them ({@code dind/e2e-local.sh} builds them)</li>
 *     <li>{@code e2e.flnet-version}: the flnet release the DinD images are built with (default
 *     {@value #DEFAULT_FLNET_VERSION}); {@code e2e.flnet-binary}: a linux musl flnet to use instead;
 *     {@code e2e.flnet-from-source}: build that one from this checkout first</li>
 *     <li>{@code e2e.dind-image-tag}: pull the published DinD images with this tag instead of building them</li>
 *     <li>{@code e2e.docker-config}: a docker config.json with registry logins for the inner daemons</li>
 *     <li>{@code e2e.timeout-minutes} (default 40), {@code e2e.build-timeout-minutes} (default 90) and
 *     {@code e2e.run-timeout-minutes} (default 30): how long the services may take to become healthy, the
 *     platform to build a tool and a run to end</li>
 * </ul>
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndToEndTest {

    private static final String DEFAULT_IMAGE_TAG = "staging";
    private static final String DEFAULT_FLNET_VERSION = "staging";

    // flnet.tool-imports.build-only of the dev profile in Learning-APIs/global-learning-api/src/main/resources/flnet.properties
    static final List<String> BUILD_ONLY_TOOLS = List.of("uci-fetch", "one-hot-encoding", "train-test-split",
            "agglomerative-clustering", "birch", "gaussian-mixture-model", "branch-merge-aggregator", "comparative-clustering-benchmark",
            "logistic-regression",
            "us130-hospital-readmission-federated-nn", "import-diabetes-130-us-hospitals-for-years-1999-2008",
            "transformer-date");

    // the app transformers of the US-130 connector, for the import with apps, and an export app, which reads a cohort's data entries
    static final List<String> E2E_TOOLS = List.of("transformer-combine-rows", "transformer-split-rows", "patient-export-umap");

    // compose services of each deployment, which e2e.images may replace
    private static final Set<String> PLATFORM_SERVICES = Set.of("datamodeler-api", "global-learning-api", "orch-api", "controller",
            "relay-server", "frontend");
    private static final Set<String> CLIENT_SERVICES = Set.of("local-learning-api", "orch-api", "controller", "instance-manager-frontend");

    // flnet.tool-imports.owner: imported tools belong to this user, so it may run them as test engines
    static final String PLATFORM_USER = "admin";
    // owner of the clients' cohorts (flnet.auto-subscribe.*.add-for-user)
    private static final String CLIENT_USER = "e2e";
    static final String PLATFORM_HOST = "flnet-platform.test";
    private static final String PLATFORM_NAME = "e2e";
    private static final String PLATFORM_PROJECT = "fl-net-platform-" + PLATFORM_NAME;
    private static final int RELAY_PORT = 9150;
    private static final String KCADM_CONFIG = "/tmp/kcadm-e2e.config";
    // builds flnet like the musl asset of 'Build and release flnet'
    private static final String FLNET_BUILD_IMAGE = "bellsoft/liberica-native-image-kit-container:jdk-25-nik-25.0.4.1-musl";

    private static final String LOGISTIC_REGRESSION = "logistic-regression";
    private static final String IRIS_TARGET = "variety";
    static final String WORKFLOW = "CSV encode to cluster";
    private static final String WORKFLOW_INPUT = "Iris";
    static final String US130 = "us130-hospital-readmission-federated-nn";
    private static final String US130_SCHEMA_ID = "c4877033-3619-47c2-bf7d-37f48a2934b3";
    // its ontologies, datatypes and schema in datamodeler-api/src/main/resources/datamodeler-imports, imported in dev only
    private static final String US130_IMPORT = "2026-04us130_diabetes_schema.json";
    private static final String US130_COHORT = "US 130 Schema";
    private static final String US130_TARGET = "hospital_readmission";
    private static final int ROUNDS = 2;
    // privacy minimum of a client's query answer (flnet.privacy.query.min-count)
    private static final int QUERY_MIN_COUNT = 100;
    private static final int CLINICS = 2;

    private static final Set<String> FINAL = Set.of("FINISHED", "STOPPED", "ERROR");
    private static final Duration POLL = Duration.ofSeconds(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path work;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    // random so the containers and the network never collide with a parallel run
    private final String run = "flnet-e2e-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt()).substring(0, 6);
    private final String platformContainer = run + "-platform";
    private final String adminPassword = secret();
    private final String clientPassword = secret();
    private final Map<String, JsonNode> apps = new HashMap<>();
    private final List<Clinic> clinics = new ArrayList<>();

    private EndToEndApi api;
    private Path flnetBinary;
    private String platformUrl;
    private JsonNode us130Query;
    // the federated project on the query of the imported clinics
    private Long us130Project;
    // the central project of logistic regression on iris
    private Long irisProject;
    private boolean started;
    private boolean networkCreated;
    private boolean deployed;
    private boolean failed;

    /** A client: its DinD container, its port on this machine and its API. */
    private record Clinic(int number, String container, int port, EndToEndApi api) {

        String instance() {
            return "clinic" + number;
        }

        String project() {
            return "fl-net-client-" + instance();
        }
    }

    @RegisterExtension
    final TestWatcher failures = new TestWatcher() {
        @Override
        public void testFailed(ExtensionContext context, Throwable cause) {
            failed = true;
        }
    };

    @Test
    @Order(1)
    void startsPlatformAndClientsInDocker() throws Exception {
        assumeTrue(ProcessHelper.probe("docker", "info", "--format", "{{.ServerVersion}}").isPresent(), "running docker daemon required");
        flnetBinary = flnet();
        String platformImage = dindImage("platform");
        String clientImage = dindImage("client");
        saveLocalImages("platform", PLATFORM_SERVICES);
        saveLocalImages("client", CLIENT_SERVICES);
        started = true;
        // a subnet of its own: the daemon's pools may be used up, e.g. by networks tool runs left behind
        docker("network", "create", "--subnet", freeSubnet(), "--label", label(), run);
        networkCreated = true;

        int platformPort = freePort();
        platformUrl = "http://localhost:" + platformPort;
        Path platformExtra = Files.writeString(work.resolve("platform-e2e.yml"), """
                # e2e: build only the tools the scenarios use, import the US-130 schema the clinics use
                services:
                  global-learning-api:
                    environment:
                      - FLNET_TOOL_IMPORTS_BUILD_ONLY=%1$s
                  datamodeler-api:
                    environment:
                      - FLNET_ONTOLOGY_IMPORTS_ENABLED=true
                      - FLNET_ONTOLOGY_IMPORTS_WHITELIST=%2$s
                      - FLNET_ONTOLOGY_IMPORTS_CREATE_EMBEDDINGS=false
                      - FLNET_DATATYPE_IMPORTS_ENABLED=true
                      - FLNET_DATATYPE_IMPORTS_WHITELIST=%2$s
                      - FLNET_SCHEMA_IMPORTS_ENABLED=true
                      - FLNET_SCHEMA_IMPORTS_WHITELIST=%2$s
                """.formatted(String.join(",", builtTools()), US130_IMPORT));
        List<String> platform = dockerRun(platformContainer, platformImage, platformExtra, "platform");
        // the same port inside and outside: the address of the platform's tool registry for its clients
        platform.addAll(List.of("--network-alias", PLATFORM_HOST, "-p", "127.0.0.1:" + platformPort + ":" + platformPort,
                "-e", "PLATFORM_NAME=" + PLATFORM_NAME, "-e", "PLATFORM_ALIASES=" + PLATFORM_HOST, "-e", "PLATFORM_PORT=" + platformPort, "-e", "RELAY_PORT=" + RELAY_PORT,
                "-e", "FLNET_PLATFORM_ARGS=--min-clients " + CLINICS, platformImage));
        docker(platform.toArray(String[]::new));

        Instant deadline = Instant.now().plus(Duration.ofMinutes(Long.getLong("e2e.timeout-minutes", 40)));
        awaitInnerHealthy(platformContainer, PLATFORM_PROJECT, "datamodeler-api", "/q/health", Set.of(), deadline);
        awaitInnerHealthy(platformContainer, PLATFORM_PROJECT, "global-learning-api", "/q/health", Set.of(), deadline);
        Map<String, Predicate<HttpResponse<String>>> checks = new LinkedHashMap<>();
        checks.put(platformUrl + "/auth/realms/FLNet-Platform", EndToEndTest::ok);
        checks.put(platformUrl + "/", EndToEndTest::ok);
        // not public: Quarkus endpoints and the data modeler (only via the global API)
        checks.put(platformUrl + "/api/q/health", EndToEndTest::notFound);
        checks.put(platformUrl + "/data-modeler/q/health", response -> response.statusCode() != 200 || !response.body().contains("\"checks\""));
        for (var check : checks.entrySet()) {
            awaitHealthy(check.getKey(), check.getValue(), deadline);
        }

        createUser(platformContainer, PLATFORM_PROJECT, "FLNet-Platform", PLATFORM_USER, adminPassword, "Admin", "Data-Scientist", "Data-Modeler");
        for (int clinic = 1; clinic <= CLINICS; clinic++) {
            createUser(platformContainer, PLATFORM_PROJECT, "FLNet-Platform", clinicUser(clinic), clientPassword);
        }
        api = EndToEndApi.platform(http, platformUrl, PLATFORM_USER, adminPassword);
        assertTrue(api.get("/store/all").isArray(), "the test user is accepted by the global API");

        // the clients log in to the platform on start, so after their accounts
        List<String> containers = new ArrayList<>();
        Map<Integer, Integer> ports = new LinkedHashMap<>();
        for (int clinic = 1; clinic <= CLINICS; clinic++) {
            ports.put(clinic, freePort());
            containers.add(startClinic(clientImage, clinic, ports.get(clinic), platformPort));
        }
        // all before waiting for one: each client imports soon after its Keycloak is up
        for (int clinic = 1; clinic <= CLINICS; clinic++) {
            importClientUser(containers.get(clinic - 1), "fl-net-client-clinic" + clinic, deadline);
        }
        for (int clinic = 1; clinic <= CLINICS; clinic++) {
            String container = containers.get(clinic - 1);
            String project = "fl-net-client-clinic" + clinic;
            String clientUrl = "http://localhost:" + ports.get(clinic);
            awaitInnerHealthy(container, project, "local-learning-api", "/q/health/live", Set.of(), deadline);
            // the websocket to the platform is checked by the next scenario
            awaitInnerHealthy(container, project, "local-learning-api", "/q/health/ready", Set.of("websocket-client-readiness"), deadline);
            Map<String, Predicate<HttpResponse<String>>> clientChecks = new LinkedHashMap<>();
            clientChecks.put(clientUrl + "/auth/realms/FLNet-Client", EndToEndTest::ok);
            clientChecks.put(clientUrl + "/", EndToEndTest::ok);
            clientChecks.put(clientUrl + "/local-learning-api/q/health", EndToEndTest::notFound);
            for (var check : clientChecks.entrySet()) {
                awaitHealthy(check.getKey(), check.getValue(), deadline);
            }
            clinics.add(new Clinic(clinic, container, ports.get(clinic), clientApi(container, project, clientUrl)));
        }
        deployed = true;
    }

    @Test
    @Order(2)
    void connectsBothClientsToThePlatform() throws Exception {
        assumeDeployed();
        Instant deadline = Instant.now().plus(Duration.ofMinutes(5));
        for (Clinic clinic : clinics) {
            awaitInnerHealthy(clinic.container(), clinic.project(), "local-learning-api", "/q/health/ready", Set.of(), deadline);
        }
    }

    @Test
    @Order(3)
    void importsEachClinicThroughTheConnector() throws Exception {
        assumeDeployed();
        List<Executable> checks = new ArrayList<>();
        for (Clinic clinic : clinics) {
            int patients = patients(fixture("us130-clinic-" + clinic.number() + ".csv"));
            JsonNode run = awaitValue(clinic.instance() + " connector run", () -> stream(clinic.api().get("/connectors/runs"))
                    .findFirst()
                    .orElse(null), found -> Set.of("FINISHED", "ERROR").contains(found.path("status").asText()), runTimeout());
            JsonNode cohort = us130Cohort(clinic);
            checks.add(() -> {
                assertEquals("FINISHED", run.path("status").asText(), clinic.instance() + " connector run\n" + run.toPrettyString());
                assertEquals(0, run.path("failedEntities").asLong(), clinic.instance() + " failed patients\n" + run.toPrettyString());
                assertEquals(US130_SCHEMA_ID, cohort.path("globalSchemaId").asText(), clinic.instance() + " cohort schema");
                assertEquals(patients, cohort.path("amountOfPatients").asInt(), clinic.instance() + " patients of the cohort\n"
                        + cohort.toPrettyString());
            });
        }
        assertAll("ETL", checks);
    }

    @Test
    @Order(4)
    void selfTestsEveryBuiltTool() throws Exception {
        assumeDeployed();
        Map<String, Long> engines = new LinkedHashMap<>();
        for (String tool : builtTools()) {
            engines.put(tool, api.post(engines(builtApp(tool)), Map.of("mode", "SELF_TEST")).path("id").asLong());
        }
        List<Executable> checks = new ArrayList<>();
        for (var engine : engines.entrySet()) {
            JsonNode app = builtApp(engine.getKey());
            JsonNode run = awaitFinal(engine.getKey() + " self-test", () -> engine(app, engine.getValue()));
            checks.add(() -> assertWithEngineLogs(app, engine.getValue(), () -> assertSelfTest(engine.getKey(), run)));
        }
        assertAll("self-tests", checks);
    }

    @Test
    @Order(5)
    void exportsACohortThroughAnApp() throws Exception {
        assumeDeployed();
        JsonNode app = builtApp("patient-export-umap");
        Clinic clinic = clinics.getFirst();
        long cohort = us130Cohort(clinic).path("id").asLong();
        ObjectNode config = JSON.readTree(fixture("us130-export-config.json").toFile()).deepCopy();
        config.put("appBased", true);
        config.put("globalAppVersionId", versionId(app));
        config.set("hyperParams", JSON.createObjectNode());
        String exports = "/cohort/" + cohort + "/data/patient/export/app";

        // the first events only say the cohort's data is read
        JsonNode started = clinic.api().postEvent(exports + "/stream", config,
                event -> event.hasNonNull("id") || "ERROR".equals(event.path("status").asText()), runTimeout());
        assertTrue(started.hasNonNull("id"), "app export started\n" + started.toPrettyString());
        long runId = started.path("id").asLong();
        JsonNode run = awaitFinal(clinic.instance() + " app export", () -> stream(clinic.api().get(exports))
                .filter(candidate -> candidate.path("id").asLong() == runId)
                .findFirst()
                .orElse(null));

        assertFinished(run);
        assertExecution(run, "PROCESSING", "SINGLE", "LOCAL");
    }

    @Test
    @Order(6)
    void reimportsAClinicThroughAppTransformers() throws Exception {
        assumeDeployed();
        Map<String, JsonNode> transformers = Map.of("Transformer Combine Rows", builtApp("transformer-combine-rows"),
                "Transformer Split Rows", builtApp("transformer-split-rows"));
        Clinic clinic = clinics.getLast();
        long cohort = us130Cohort(clinic).path("id").asLong();
        // the connector of the clinic with its diagnoses combined and split again by the apps, so the data stays the same
        ObjectNode connector = stream(clinic.api().get("/connectors?cohort_id=" + cohort))
                .findFirst()
                .orElseGet(() -> fail(clinic.instance() + " has no connector"))
                .deepCopy();
        ArrayNode steps = JSON.createArrayNode();
        for (JsonNode step : JSON.readTree(fixture("us-130-app-transform.json").toFile()).path("transformer")) {
            ObjectNode copy = step.deepCopy();
            if ("app".equals(step.path("moduleName").asText())) {
                JsonNode app = Optional.ofNullable(transformers.get(step.path("methodName").asText()))
                        .orElseGet(() -> fail("no tool for the transformer " + step.path("methodName").asText()));
                copy.put("appVersionId", versionId(app));
                copy.put("appImage", latestVersion(app).path("imageName").asText());
            }
            steps.add(copy);
        }
        connector.set("transformer", steps);
        connector.put("name", "e2e app transformers");
        long id = clinic.api().post("/connectors?raw=true", connector).path("id").asLong();

        // its response streams the run's updates as JSON while it runs, so it stays open meanwhile
        clinic.api().postInBackground("/connectors/" + id + "/run", "");
        JsonNode run = awaitValue(clinic.instance() + " connector run with apps", () -> stream(clinic.api().get("/connectors/runs"))
                        .filter(candidate -> candidate.path("connectorId").asLong() == id)
                        .findFirst()
                        .orElse(null),
                found -> Set.of("FINISHED", "ERROR").contains(found.path("status").asText()), runTimeout());

        assertEquals("FINISHED", run.path("status").asText(), "connector run with apps\n" + run.toPrettyString());
        assertEquals(0, run.path("failedEntities").asLong(), "failed patients\n" + run.toPrettyString());
        assertEquals(patients(fixture("us130-clinic-" + clinic.number() + ".csv")), us130Cohort(clinic).path("amountOfPatients").asInt(),
                "patients of the cohort after the import with apps");
    }

    @Test
    @Order(7)
    void runsLogisticRegressionAsSingleAppAnalysis() throws Exception {
        assumeDeployed();
        JsonNode app = builtApp(LOGISTIC_REGRESSION);
        long analysis = createAnalysis("e2e single app");
        String iris = uploadToAnalysis(analysis, "iris.csv");

        JsonNode run = runInAnalysis(analysis, Map.of("appVersionId", versionId(app), "inputs", Map.of("data", iris),
                "hyperParams", Map.of("target_column", IRIS_TARGET)));

        assertFinished(run);
        assertExecution(run, "ANALYSIS", "SINGLE", "PLATFORM");
        assertOutputFiles(analysis, run);
    }

    @Test
    @Order(8)
    void runsCsvEncodeToClusterWorkflow() throws Exception {
        assumeDeployed();
        JsonNode workflow = stream(api.get("/workflow"))
                .filter(candidate -> WORKFLOW.equals(candidate.path("name").asText()))
                .findFirst()
                .orElseGet(() -> fail("Bundled workflow '" + WORKFLOW + "' not imported"));
        for (String tool : List.of("one-hot-encoding", "agglomerative-clustering")) {
            builtApp(tool);
        }
        long analysis = createAnalysis("e2e workflow");
        String iris = uploadToAnalysis(analysis, "iris.csv");

        JsonNode run = runInAnalysis(analysis, Map.of("workflowId", workflow.path("id").asLong(), "inputs", Map.of(WORKFLOW_INPUT, iris)));

        assertFinished(run);
        assertExecution(run, "ANALYSIS", "SINGLE", "PLATFORM");
        assertEquals(workflow.path("nodes").size(), run.path("steps").size(), "a step per node\n" + run.toPrettyString());
    }

    @Test
    @Order(9)
    void trainsPublishesAndPredictsWithLogisticRegression() throws Exception {
        assumeDeployed();
        JsonNode app = builtApp(LOGISTIC_REGRESSION);
        String iris = uploadToFileStore("iris.csv");
        long engine = startConnectedEngine(app);
        JsonNode version;
        try {
            JsonNode run = withEngineLogs(app, engine, () -> runExperiment(app, iris));
            assertFinished(run);
            assertExecution(run, "TEST", "SINGLE", "PLATFORM");
            assertGridSearched(run);

            long experiment = run.path("experiment").path("id").asLong();
            version = awaitValue("model version of experiment " + experiment,
                    () -> api.find("/model/experiment/" + experiment), found -> found.hasNonNull("id"), runTimeout());
        } finally {
            stopEngine(app, engine);
        }
        long versionId = version.path("id").asLong();
        ObjectNode publish = version.deepCopy();
        publish.put("modelVersion", "1.0.0");
        publish.put("changelog", "Published by the end-to-end test");
        publish.put("publishStatus", "PUBLISHED");
        assertEquals("PUBLISHED", api.put("/model/" + versionId + "/version/" + versionId, publish).path("publishStatus").asText());
        boolean published = stream(api.get("/model/" + version.path("modelId").asLong()).path("modelVersions"))
                .anyMatch(listed -> listed.path("id").asLong() == versionId && "PUBLISHED".equals(listed.path("publishStatus").asText()));
        assertTrue(published, "model version " + versionId + " is published");

        // the published model predicts in a data analysis
        JsonNode subModel = version.hasNonNull("selectedSubModel") ? version.path("selectedSubModel") : version.path("subModels").path(0);
        assertTrue(subModel.hasNonNull("id"), "sub model of version " + versionId + "\n" + version.toPrettyString());
        // the model runs from its own image, a model pipeline builds it from the tool and the model's files
        long pipeline = api.post("/pipeline", Map.of("pipelineType", "MODEL", "modelSubId", subModel.path("id").asLong(), "autoStart", true))
                .path("id").asLong();
        JsonNode built = awaitValue("image of sub model " + subModel.path("id"), () -> api.get("/pipeline/" + pipeline),
                found -> Set.of("SUCCESS", "WARNING", "STOPPED", "FAILED").contains(found.path("pipelineStatus").asText()), runTimeout());
        assertTrue(Set.of("SUCCESS", "WARNING").contains(built.path("pipelineStatus").asText()), "model pipeline\n" + built.toPrettyString());
        long analysis = createAnalysis("e2e prediction");
        String input = uploadToAnalysis(analysis, "iris.csv");
        JsonNode prediction = runInAnalysis(analysis, Map.of("modelSubId", subModel.path("id").asLong(), "modelVersionId", versionId,
                "inputs", Map.of("data", input), "hyperParams", Map.of("target_column", IRIS_TARGET)));
        assertFinished(prediction);
        assertExecution(prediction, "ANALYSIS", "SINGLE", "PLATFORM");
        assertOutputFiles(analysis, prediction);
    }

    @Test
    @Order(10)
    void servesSingleRunAndEngineTestRuns() throws Exception {
        assumeDeployed();
        JsonNode app = builtApp(LOGISTIC_REGRESSION);
        String iris = uploadToFileStore("iris.csv");
        Map<String, Object> testRun = Map.of("federatedAppVersionId", versionId(app),
                "hyperParams", Map.of("target_column", IRIS_TARGET), "inputs", Map.of("data", iris));

        // a single run: the engine serves the test run it was started with, then ends
        long single = startEngine(app, Map.of("mode", "SINGLE_RUN", "testRun", testRun));
        JsonNode singleEngine = awaitFinal("single run engine", () -> engine(app, single));
        JsonNode singleRun = awaitFinal("test run of the single run engine", () -> stream(api.get("/runs/test/" + appId(app)))
                .filter(candidate -> candidate.path("testEngineId").asLong() == single)
                .findFirst()
                .orElse(null));
        assertWithEngineLogs(app, single, () -> {
            assertFinished(singleEngine);
            assertFinished(singleRun);
        });

        // an engine serves a test run started by the developer's frontend and an experiment until it is stopped
        long engine = startConnectedEngine(app);
        try {
            JsonNode run = withEngineLogs(app, engine, () -> {
                Set<Long> before = ids(api.get("/runs/test/" + appId(app)));
                try (EndToEndApi.TestEmbed socket = api.testEmbed(appId(app))) {
                    socket.send("START_RUN", testRun, "TEST_RUN");
                    long id = awaitNew("test run started over the socket", "/runs/test/" + appId(app), before);
                    return awaitFinal("test run", () -> api.get("/runs/test/" + appId(app) + "/run/" + id));
                }
            });
            assertFinished(run);
            assertExecution(run, "TEST", "SINGLE", "PLATFORM");
            assertEquals(engine, run.path("testEngineId").asLong(), "the engine served the test run");

            JsonNode experiment = withEngineLogs(app, engine, () -> runExperiment(app, iris));
            assertFinished(experiment);
            assertEquals(engine, experiment.path("testEngineId").asLong(), "the engine served the experiment");
        } finally {
            JsonNode stopped = stopEngine(app, engine);
            assertTrue(Set.of("FINISHED", "STOPPED").contains(stopped.path("status").asText()), "stopped engine\n" + stopped.toPrettyString());
            assertTrue(absent(stopped.path("failure")), "stopped engine without failure\n" + stopped.toPrettyString());
        }
    }

    @Test
    @Order(11)
    void trainsLogisticRegressionOnThePlatform() throws Exception {
        assumeDeployed();
        JsonNode app = builtApp(LOGISTIC_REGRESSION);
        long project = api.post("/project", Map.of("name", "e2e iris", "description", "Training on the platform of the end-to-end test"))
                .path("id").asLong();
        irisProject = project;
        api.upload("/project/" + project + "/files/upload", fixture("iris.csv"));
        projectWorkflow(project, app, Map.of("target_column", IRIS_TARGET), false);

        String trainings = "/project/" + project + "/experiment/local";
        long runId = api.post(trainings, Map.of("name", "e2e iris", "description", "Training on the platform of the end-to-end test"))
                .path("id").asLong();
        api.put(trainings + "/" + runId + "/start", "");
        JsonNode run = awaitFinal("training on the platform", () -> api.get(trainings + "/" + runId + "/detail"));

        assertFinished(run);
        assertExecution(run, "TRAINING", "SINGLE", "PLATFORM");
    }

    @Test
    @Order(12)
    void answersFederatedQueryAndStatistics() throws Exception {
        assumeDeployed();
        long query = us130Query().path("id").asLong();
        api.post("/query/" + query + "/fire-data-statistics", "");
        JsonNode responses = awaitValue("data statistics of both clients", () -> api.get("/query/data-statistics/query/" + query),
                found -> found.size() >= CLINICS, runTimeout());
        assertEquals(CLINICS, stream(responses).map(response -> response.path("randomClinicId").asText()).distinct().count(),
                "a statistics answer per client\n" + responses.toPrettyString());
        stream(responses).forEach(response -> assertFalse(absent(response.path("statistics")), "statistics\n" + response.toPrettyString()));
    }

    @Test
    @Order(13)
    void trainsUs130FederatedAcrossBothClients() throws Exception {
        assumeDeployed();
        JsonNode app = builtApp(US130);
        JsonNode exportConfig = JSON.readTree(fixture("us130-export-config.json").toFile());

        long project = api.post("/project", Map.of("name", "e2e US-130", "description", "Federated training of the end-to-end test",
                "queryId", us130Query().path("id").asLong())).path("id").asLong();
        ObjectNode detail = api.get("/project/" + project).deepCopy();
        detail.set("exportConfig", exportConfig);
        api.put("/project/" + project, detail);
        projectWorkflow(project, app, Map.of("federated_rounds", ROUNDS, "target_column", US130_TARGET), true);

        long runId = api.post("/project/" + project + "/experiment/federated", Map.of("name", "e2e US-130",
                "description", "Federated training of the end-to-end test")).path("id").asLong();
        awaitValue("both clients accepting the training", () -> federatedTraining(project, runId),
                found -> "READY".equals(found.path("experiment").path("status").asText()) && found.path("participants").size() == CLINICS,
                runTimeout());
        api.put("/project/" + project + "/experiment/federated/" + runId + "/start", "");
        JsonNode training = awaitFinal("federated training", () -> federatedTraining(project, runId));

        assertFinished(training);
        assertExecution(training, "TRAINING", "FEDERATED", "LOCAL");
        assertEquals(CLINICS, training.path("participants").size(), "participants\n" + training.toPrettyString());
        assertRounds(training);
        assertClinicAttempts(training);
        long experiment = training.path("experiment").path("id").asLong();
        awaitValue("model version of federated experiment " + experiment, () -> api.find("/model/experiment/federated/" + experiment),
                found -> found.hasNonNull("id"), runTimeout());
        us130Project = project;

        // each client ran its part of the training on the data the project exported
        List<Executable> checks = new ArrayList<>();
        for (Clinic clinic : clinics) {
            JsonNode local = awaitValue(clinic.instance() + " training", () -> stream(clinic.api().get("/learning/projects").path("results"))
                    .filter(candidate -> "e2e US-130".equals(candidate.path("project").path("name").asText()))
                    .findFirst()
                    .orElse(null), found -> found.path("project").hasNonNull("id"), Duration.ofMinutes(2));
            long localProject = local.path("project").path("id").asLong();
            JsonNode run = awaitFinal(clinic.instance() + " training run", () -> clinic.api().get("/learning/projects/" + localProject + "/run"));
            String data = clinic.api().getText("/learning/projects/" + localProject + "/export");
            checks.add(() -> {
                assertFinished(run);
                assertExecution(run, "TRAINING", "FEDERATED", "LOCAL");
                assertTrue(data.lines().findFirst().orElse("").contains(US130_TARGET), clinic.instance() + " training data columns: "
                        + data.lines().findFirst().orElse(""));
            });
        }
        assertAll("client side of the training", checks);

        // the clients report their training metrics on request
        String metrics = "/project/" + project + "/experiment/federated/" + experiment + "/metrics-request";
        api.post(metrics, "");
        JsonNode request = awaitValue("metrics of both clients", () -> api.get(metrics),
                found -> found.path("totalParticipants").asInt() == CLINICS && found.path("responsesReceived").asInt() >= CLINICS, runTimeout());
        assertEquals(CLINICS, request.path("responses").size(), "metrics responses\n" + request.toPrettyString());
        assertFalse(absent(api.get(metrics + "/evaluation")), "evaluation summary of the training");
    }

    @Test
    @Order(14)
    void testsTheCentralProjectOnItsData() throws Exception {
        assumeDeployed();
        assumeTrue(irisProject != null, "the central project was not set up");
        // a central project, the federated app needs its federation; the test runs on the project's file, iris
        String test = "/project/" + irisProject + "/experiment/local/test";
        long runId = api.post(test, "").path("id").asLong();
        JsonNode run = api.getEvent(test, found -> found.path("id").asLong() == runId && FINAL.contains(found.path("status").asText()),
                runTimeout());

        assertFinished(run);
        assertExecution(run, "TRAINING", "SINGLE", "PLATFORM");
        assertTrue(run.path("experiment").path("test").asBoolean(), "a test of the project\n" + run.toPrettyString());
    }

    // a federated test run of the developer's frontend, the platform's relay and controller connect the participants
    @Test
    @Order(15)
    void runsFederatedTestRunOnAnEngine() throws Exception {
        assumeDeployed();
        assumeTrue(us130Project != null, "the federated project was not set up");
        JsonNode app = builtApp(US130);
        List<Map<String, Object>> participants = new ArrayList<>();
        for (var participant : List.of(Map.entry("client-1", "CLIENT"), Map.entry("client-2", "CLIENT"), Map.entry("aggregator", "AGGREGATOR"))) {
            participants.add(Map.of("participantId", participant.getKey(), "role", participant.getValue(),
                    "hyperParams", Map.of("federated_rounds", ROUNDS, "target_column", US130_TARGET)));
        }
        // the platform puts dummy data of the project's export into the engine, the project is on the query of the imported clinics
        // polling of the dockerized controller as for a real federated run; a test run's default (500 * 0.01s) suits the in-memory one
        Map<String, Object> create = Map.of("federatedAppVersionId", versionId(app), "totalRounds", ROUNDS, "participants", participants,
                "projectId", us130Project, "config", Map.of("pollInterval", 0.25, "maxPolls", 2400, "timeout", 600));
        long engine = startConnectedEngine(app);
        try {
            JsonNode run = withEngineLogs(app, engine, () -> {
                Set<Long> before = ids(api.get("/runs/federated/" + appId(app)));
                try (EndToEndApi.TestEmbed socket = api.testEmbed(appId(app))) {
                    socket.send("START_FEDERATED_RUN", create, "FEDERATED_RUN");
                    long id = awaitNew("federated test run started over the socket", "/runs/federated/" + appId(app), before);
                    return awaitFinal("federated test run", () -> api.get("/runs/federated/" + appId(app) + "/run/" + id));
                }
            });
            assertFinished(run);
            assertExecution(run, "TEST", "FEDERATED", "PLATFORM");
            assertEquals(engine, run.path("testEngineId").asLong(), "the engine served the federated test run");
            assertEquals(participants.size(), run.path("participants").size(), "participants\n" + run.toPrettyString());
            assertRounds(run);
        } finally {
            stopEngine(app, engine);
        }
    }

    @AfterAll
    void cleanUp() {
        if (!started) { // skipped before anything was created
            return;
        }
        if (failed || !deployed) {
            diagnose(platformContainer, "platform", PLATFORM_NAME);
            for (int clinic = 1; clinic <= CLINICS; clinic++) {
                diagnose(clinicContainer(clinic), "client", "clinic" + clinic);
            }
        }
        List<String> failures = new ArrayList<>();
        String containers = ProcessHelper.output("docker", "ps", "-aq", "--filter", "label=" + label()).orElse("").strip();
        // again after a failure: removing a DinD container with its large inner storage fails now and then
        for (int attempt = 1; !containers.isEmpty(); attempt++) {
            List<String> remove = new ArrayList<>(List.of("docker", "rm", "-f", "-v"));
            remove.addAll(Arrays.asList(containers.split("\\s+")));
            if (ProcessHelper.runInteractive(work, remove) == 0) {
                break;
            }
            if (attempt == 3) {
                failures.add("containers not removed: " + containers.replace('\n', ' '));
                break;
            }
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            containers = ProcessHelper.output("docker", "ps", "-aq", "--filter", "label=" + label()).orElse("").strip();
        }
        if (networkCreated && ProcessHelper.output("docker", "network", "rm", run).isEmpty()) {
            failures.add("network " + run + " not removed");
        }
        leftovers("containers", List.of("docker", "ps", "-aq", "--filter", "label=" + label()), failures);
        leftovers("networks", List.of("docker", "network", "ls", "-q", "--filter", "label=" + label()), failures);
        if (!failures.isEmpty()) {
            fail("Clean up incomplete:\n" + String.join("\n", failures));
        }
    }

    /**
     * A run that ended well: FINISHED without failure and, in every step, the selected attempt FINISHED without
     * failure and with exit code 0 or none.
     */
    static void assertFinished(JsonNode run) {
        String json = run.toPrettyString();
        assertEquals("FINISHED", run.path("status").asText(), "status of run " + run.path("id") + "\n" + json);
        assertTrue(absent(run.path("failure")), "failure of run " + run.path("id") + "\n" + json);
        assertFalse(run.path("steps").isEmpty(), "run " + run.path("id") + " without steps\n" + json);
        for (JsonNode step : run.path("steps")) {
            JsonNode selected = selectedAttempt(step);
            assertNotNull(selected, "selected attempt of step " + step.path("id") + "\n" + json);
            assertEquals("FINISHED", selected.path("status").asText(), "status of attempt " + selected.path("id") + "\n" + json);
            assertTrue(absent(selected.path("failure")), "failure of attempt " + selected.path("id") + "\n" + json);
            JsonNode exitCode = selected.path("exitCode");
            assertTrue(absent(exitCode) || exitCode.asInt() == 0, "exit code of attempt " + selected.path("id") + "\n" + json);
        }
    }

    // The container ran and its exit code settled the run; the tool's own result is not checked, the build pipeline lets
    // a self-test fail as well (allow_to_fail of its test step), and some tools ship no test data for the generated input.
    private static void assertSelfTest(String tool, JsonNode run) {
        String json = run.toPrettyString();
        assertExecution(run, "TEST_HOSTING", "SINGLE", "PLATFORM");
        JsonNode attempt = attemptOf(run.path("steps").get(0));
        assertTrue(attempt.hasNonNull("exitCode"), "exit code of the self-test of " + tool + "\n" + json);
        int exitCode = attempt.path("exitCode").asInt();
        assertEquals(exitCode == 0 ? "FINISHED" : "ERROR", run.path("status").asText(), "status of the self-test of " + tool + "\n" + json);
        if (exitCode != 0) {
            assertTrue(run.path("failure").path("message").asText().contains("exited with code " + exitCode),
                    "failure of the self-test of " + tool + "\n" + json);
            System.out.printf("Self-test of %s exited with code %d%n", tool, exitCode);
        }
    }

    // two trials of the grid, the selected one is the best as the search ranks them: by its metric, else the first finished
    private static void assertGridSearched(JsonNode run) {
        String json = run.toPrettyString();
        JsonNode step = run.path("steps").get(0);
        List<JsonNode> trials = stream(step.path("attempts")).toList();
        assertEquals(2, trials.size(), "trials\n" + json);
        assertEquals(Set.of(0, 1), Set.copyOf(trials.stream().map(trial -> trial.path("trialIndex").asInt()).toList()), "trial indices\n" + json);
        String metric = step.path("search").path("metric").asText("");
        boolean maximize = !"MINIMIZE".equals(step.path("search").path("direction").asText());
        Comparator<JsonNode> byScore = Comparator.comparing((JsonNode trial) -> score(trial, metric),
                Comparator.nullsLast(maximize ? Comparator.<Double>reverseOrder() : Comparator.<Double>naturalOrder()));
        JsonNode best = trials.stream()
                .filter(trial -> "FINISHED".equals(trial.path("status").asText()))
                .min((metric.isBlank() ? Comparator.<JsonNode>comparingInt(trial -> 0) : byScore)
                        .thenComparingInt(trial -> trial.path("trialIndex").asInt()))
                .orElseGet(() -> fail("no finished trial\n" + json));
        assertEquals(best.path("id").asLong(), step.path("selectedAttemptId").asLong(), "selected trial\n" + json);
    }

    private static Double score(JsonNode trial, String metric) {
        JsonNode score = trial.path("scoreMetric");
        if (!metric.equals(score.path("metric").asText())) {
            return null;
        }
        try {
            double value = Double.parseDouble(score.path("value").asText().strip());
            return Double.isNaN(value) ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void assertRounds(JsonNode run) {
        JsonNode federation = run.path("steps").get(0).path("federation");
        assertEquals(ROUNDS, federation.path("totalRounds").asInt(), "rounds\n" + run.toPrettyString());
    }

    // a finished attempt per clinic in every step, each through all rounds
    private static void assertClinicAttempts(JsonNode run) {
        String json = run.toPrettyString();
        for (JsonNode step : run.path("steps")) {
            List<JsonNode> attempts = stream(step.path("attempts")).toList();
            assertEquals(CLINICS, attempts.size(), "attempts of step " + step.path("id") + "\n" + json);
            assertEquals(CLINICS, attempts.stream().map(attempt -> attempt.path("participantId").asText()).distinct().count(),
                    "an attempt per clinic in step " + step.path("id") + "\n" + json);
            attempts.forEach(attempt -> assertEquals("FINISHED", attempt.path("status").asText(), "status of attempt " + attempt.path("id") + "\n" + json));
        }
        assertEquals(ROUNDS, run.path("steps").get(0).path("federation").path("currentRound").asInt(), "current round\n" + json);
    }

    private void assertOutputFiles(long analysis, JsonNode run) {
        long attempt = attemptOf(run.path("steps").get(0)).path("id").asLong();
        List<JsonNode> outputs = stream(api.get("/model/workflow/" + analysis + "/files"))
                .filter(file -> file.path("attemptId").asLong() == attempt && absent(file.path("inputName")))
                .toList();
        assertFalse(outputs.isEmpty(), "output files of the run\n" + run.toPrettyString());
    }

    private static JsonNode selectedAttempt(JsonNode step) {
        long selected = step.path("selectedAttemptId").asLong(-1);
        return stream(step.path("attempts")).filter(attempt -> attempt.path("id").asLong() == selected).findFirst().orElse(null);
    }

    // the selected attempt, or the only one before one is selected
    private static JsonNode attemptOf(JsonNode step) {
        JsonNode selected = selectedAttempt(step);
        return selected != null || step.path("attempts").size() != 1 ? selected : step.path("attempts").iterator().next();
    }

    private JsonNode runInAnalysis(long analysis, Map<String, Object> start) throws InterruptedException {
        long runId = api.postFirstEvent("/model/run/workflow/" + analysis, start, runTimeout()).path("id").asLong();
        return awaitFinal("run " + runId + " of data analysis " + analysis, () -> api.get("/model/workflow/" + analysis + "/experiment/" + runId));
    }

    private JsonNode runExperiment(JsonNode app, String input) throws Exception {
        Map<String, Object> create = Map.of("name", "e2e grid", "description", "Grid search of the end-to-end test",
                "federatedAppVersionId", versionId(app),
                "inputData", JSON.writeValueAsString(Map.of("data", input)),
                "hyperParams", Map.of("C", List.of(0.5, 1.0), "target_column", List.of(IRIS_TARGET)));
        long experiment = api.post("/runs/experiment/" + appId(app) + "/experiment", create).path("experiment").path("id").asLong();
        return awaitFinal("experiment " + experiment, () -> api.get("/runs/experiment/" + appId(app) + "/experiment/" + experiment));
    }

    // a query on the US-130 outcome both clients answered; repeated as a new query, a repeated one is not answered again
    private JsonNode us130Query() throws Exception {
        if (us130Query == null) {
            JsonNode target = stream(JSON.readTree(fixture("us130-export-config.json").toFile()).path("features"))
                    .filter(feature -> US130_TARGET.equals(feature.path("name").asText()))
                    .findFirst().orElseThrow().path("allowedDataIds").get(0);
            us130Query = awaitValue("query answered by both clients", () -> fireQuery(target),
                    answered -> answered.path("result").asInt() >= CLINICS * QUERY_MIN_COUNT, runTimeout());
        }
        return us130Query;
    }

    private JsonNode fireQuery(JsonNode target) {
        Map<String, Object> item = Map.of("ontologyId", target.path("globalOntologyId").asText(),
                "dataTypeId", target.path("globalDataTypeId").asText(), "operator", List.of(Map.of("operator", "EXISTS")));
        long id = api.post("/query/fire", Map.of("name", "e2e US-130", "description", "Patients with a readmission outcome",
                "query", List.of(item))).path("id").asLong();
        try {
            return awaitValue("query " + id, () -> api.get("/query/" + id), answered -> answered.path("hasResult").asBoolean(),
                    Duration.ofMinutes(1));
        } catch (AssertionError e) {
            return api.get("/query/" + id); // a later one may get both answers
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private JsonNode federatedTraining(long project, long runId) {
        return stream(api.get("/project/" + project + "/experiment/federated"))
                .filter(candidate -> candidate.path("id").asLong() == runId)
                .findFirst()
                .orElse(null);
    }

    private long createAnalysis(String analysisName) {
        return api.post("/model/workflow", Map.of("name", analysisName)).path("id").asLong();
    }

    // the reference a data analysis run takes for a file of the analysis
    private String uploadToAnalysis(long analysis, String fixture) {
        return "file://" + api.upload("/model/workflow/" + analysis + "/files/upload", fixture(fixture)).path("id").asLong();
    }

    /**
     * Uploads to the file store and returns the secret link a tool container downloads it by: the address of the
     * global API on the platform network, the one the API builds holds the origin of this request.
     */
    private String uploadToFileStore(String fixture) {
        JsonNode file = api.upload("/files/upload", fixture(fixture));
        return "http://" + PLATFORM_PROJECT + "-global-learning-api:8080/files/" + file.path("id").asLong()
                + "/download?secret=" + file.path("secret").asText();
    }

    // the store entry of a tool once the platform built its latest version
    private JsonNode builtApp(String slug) throws InterruptedException {
        JsonNode app = apps.get(slug);
        if (app == null) {
            Duration timeout = Duration.ofMinutes(Long.getLong("e2e.build-timeout-minutes", 90));
            app = awaitValue("image of " + slug, () -> api.find("/store/app/" + slug),
                    found -> !latestVersion(found).path("imageName").asText("").isBlank(), timeout);
            apps.put(slug, app);
        }
        return app;
    }

    private static JsonNode latestVersion(JsonNode app) {
        return stream(app.path("versions"))
                .filter(version -> version.path("id").asLong() == app.path("latestVersionId").asLong())
                .findFirst()
                .orElse(JSON.missingNode());
    }

    private static List<String> builtTools() {
        return Stream.concat(BUILD_ONLY_TOOLS.stream(), E2E_TOOLS.stream()).toList();
    }

    // a workflow of the project with one node of the tool, which reads the project's data
    private void projectWorkflow(long project, JsonNode app, Map<String, Object> hyperParams, boolean federated) {
        ObjectNode workflow = api.post("/workflow", Map.of("projectId", project)).deepCopy();
        ObjectNode node = JSON.createObjectNode()
                .put("nodeId", UUID.randomUUID().toString())
                .put("federatedAppId", appId(app))
                .put("federatedAppVersionId", versionId(app))
                .put("supportsFederatedLearning", federated)
                .put("isTrainable", true)
                .put("executionOrder", 0);
        node.set("position", JSON.createObjectNode().put("x", 150).put("y", 200));
        node.set("hyperParams", JSON.valueToTree(hyperParams));
        workflow.set("nodes", JSON.createArrayNode().add(node));
        workflow.set("connections", JSON.createArrayNode());
        workflow.set("inputs", JSON.createArrayNode());
        workflow.put("name", "e2e project " + project);
        api.put("/workflow/" + workflow.path("id").asLong(), workflow);
    }

    private static JsonNode us130Cohort(Clinic clinic) {
        return stream(clinic.api().get("/cohort"))
                .filter(candidate -> US130_COHORT.equals(candidate.path("name").asText()))
                .findFirst()
                .orElseGet(() -> fail(clinic.instance() + " has no cohort '" + US130_COHORT + "'"));
    }

    // what kind of execution a run is
    private static void assertExecution(JsonNode run, String purpose, String mode, String location) {
        assertEquals(List.of(purpose, mode, location), List.of(run.path("purpose").asText(), run.path("mode").asText(),
                run.path("location").asText()), "purpose, mode and location of run " + run.path("id") + "\n" + run.toPrettyString());
    }

    private static long appId(JsonNode app) {
        return app.path("id").asLong();
    }

    private static long versionId(JsonNode app) {
        return app.path("latestVersionId").asLong();
    }

    private static String engines(JsonNode app) {
        return "/runs/test-engine/" + appId(app);
    }

    private JsonNode engine(JsonNode app, long id) {
        return stream(api.get(engines(app))).filter(candidate -> candidate.path("id").asLong() == id).findFirst().orElse(null);
    }

    private long startConnectedEngine(JsonNode app) throws Exception {
        long id = startEngine(app, Map.of("mode", "ENGINE"));
        JsonNode engine = awaitValue("engine " + id + " connected", () -> engine(app, id), found -> FINAL.contains(found.path("status").asText())
                || "RUNNING".equals(Optional.ofNullable(attemptOf(found.path("steps").get(0))).map(attempt -> attempt.path("status").asText()).orElse("")),
                runTimeout());
        assertWithEngineLogs(app, id, () -> assertFalse(FINAL.contains(engine.path("status").asText()), "engine ended before it connected\n"
                + engine.toPrettyString()));
        return id;
    }

    // the id of a new engine of the tool; the socket of one stopped just before closes a few seconds after its run ended
    private long startEngine(JsonNode app, Map<String, Object> create) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        while (true) {
            try {
                return api.post(engines(app), create).path("id").asLong();
            } catch (AssertionError e) {
                if (!String.valueOf(e.getMessage()).contains("Stop the local engine of this tool first") || Instant.now().isAfter(deadline)) {
                    throw e;
                }
                Thread.sleep(POLL.toMillis());
            }
        }
    }

    // stops an engine that still runs and returns it once it ended
    private JsonNode stopEngine(JsonNode app, long id) throws InterruptedException {
        JsonNode engine = engine(app, id);
        if (engine != null && !FINAL.contains(engine.path("status").asText())) {
            api.post(engines(app) + "/" + id + "/stop", "");
        }
        return awaitFinal("engine " + id, () -> engine(app, id));
    }

    // the id of the first run of the list that was not in it before
    private long awaitNew(String what, String list, Set<Long> before) throws InterruptedException {
        return awaitValue(what, () -> stream(api.get(list))
                .filter(candidate -> !before.contains(candidate.path("id").asLong()))
                .findFirst()
                .orElse(null), Objects::nonNull, Duration.ofMinutes(2)).path("id").asLong();
    }

    private <T> T withEngineLogs(JsonNode app, long engine, ThrowingSupplier<T> check) throws Exception {
        try {
            return check.get();
        } catch (AssertionError e) {
            List<String> logs = new ArrayList<>();
            try {
                api.get(engines(app) + "/" + engine + "/logs").forEach(line -> logs.add(line.asText()));
            } catch (RuntimeException | AssertionError unavailable) {
                logs.add("(logs unavailable: " + unavailable.getMessage() + ")");
            }
            List<String> tail = logs.subList(Math.max(0, logs.size() - 80), logs.size());
            AssertionError withLogs = new AssertionError(e.getMessage() + "\nLast logs of test engine " + engine + ":\n" + String.join("\n", tail), e);
            withLogs.setStackTrace(e.getStackTrace());
            throw withLogs;
        }
    }

    private void assertWithEngineLogs(JsonNode app, long engine, Executable check) throws Exception {
        withEngineLogs(app, engine, () -> {
            try {
                check.execute();
            } catch (Exception | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private JsonNode awaitFinal(String what, Supplier<JsonNode> fetch) throws InterruptedException {
        return awaitValue(what + " to end", fetch, found -> FINAL.contains(found.path("status").asText()), runTimeout());
    }

    private static JsonNode awaitValue(String what, Supplier<JsonNode> fetch, Predicate<JsonNode> done, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            last = fetch.get();
            if (last != null && !last.isNull() && done.test(last)) {
                return last;
            }
            Thread.sleep(POLL.toMillis());
        }
        return fail("Waited " + timeout + " for " + what + ". Last state: " + (last == null ? "none" : last.toPrettyString()));
    }

    private static Duration runTimeout() {
        return Duration.ofMinutes(Long.getLong("e2e.run-timeout-minutes", 30));
    }

    private void assumeDeployed() {
        assumeTrue(deployed, "the deployment did not come up");
    }

    /**
     * Starts a client taking part in federation with the platform; on start it imports its half of the US-130
     * test data through the connector of the paper's clinics.
     */
    private String startClinic(String image, int clinic, int port, int platformPort) throws IOException {
        Path data = Files.createDirectories(work.resolve("clinic" + clinic));
        Files.copy(fixture("us130-clinic-" + clinic + ".csv"), data.resolve("clinic.csv"));
        Files.copy(fixture("us-130-clinics.json"), data.resolve("us-130-clinics.json"));
        String platform = PLATFORM_HOST + ":" + platformPort;
        Path extra = Files.writeString(work.resolve("clinic" + clinic + "-e2e.yml"), """
                # e2e: import a clinic on start and reach the platform on its port
                services:
                  local-learning-api:
                    environment:
                      # the CLI leaves out the port, a platform on 80 or 443 needs none
                      - FLNET_GLOBAL_SOCKET_URI=ws://%s/api
                      # Keycloak's discovery names localhost, the platform's address for its own host
                      - QUARKUS_OIDC_CLIENT_GLOBAL_WEBSOCKET_DISCOVERY_ENABLED=false
                      - QUARKUS_OIDC_CLIENT_GLOBAL_WEBSOCKET_TOKEN_PATH=/protocol/openid-connect/token
                      - flnet.auto-subscribe.enabled=true
                      - flnet.auto-subscribe.us-130.global-schema-id=%s
                      - flnet.auto-subscribe.us-130.cohort-name=%s
                      - flnet.auto-subscribe.us-130.etl-data-path=/e2e/clinic.csv
                      - flnet.auto-subscribe.us-130.connector-path=/e2e/us-130-clinics.json
                      - flnet.auto-subscribe.us-130.add-for-user=%s
                    volumes:
                      - /e2e:/e2e:ro
                  controller:
                    environment:
                      # the relay certificate is self-signed
                      - RELAY_MODE=dev
                      - RELAY_TLS_MODE=self-signed
                  orch-api:
                    environment:
                      # the platform's tool images name its own address, forwarded to it (DIND_FORWARDS)
                      - ORCH_DOCKER__PRIVATE__REGISTRY_URL=localhost:%s
                """.formatted(platform, US130_SCHEMA_ID, US130_COHORT, CLIENT_USER, platformPort));
        String container = clinicContainer(clinic);
        List<String> command = dockerRun(container, image, extra, "client");
        command.addAll(List.of("-p", "127.0.0.1:" + port + ":80", "-v", data + ":/e2e:ro",
                "-e", "CLINIC_NAME=clinic" + clinic, "-e", "DEPLOYED_ON_ADDRESS=http://localhost:" + port,
                "-e", "PLATFORM_USERNAME=" + clinicUser(clinic), "-e", "FLNET_PLATFORM_PASSWORD=" + clientPassword,
                "-e", "DIND_HOSTS=" + PLATFORM_HOST, "-e", "DIND_INSECURE_REGISTRIES=" + platform,
                "-e", "DIND_FORWARDS=" + platformPort + "=" + platform,
                "-e", "FLNET_CLIENT_ARGS=--network custom --platform-url http://" + platform + " --platform-relay-port " + RELAY_PORT
                        + " --federation --default-permission --allow-queries --query-retry-time 0"
                        + " --allow-auto-learning --auto-learning all --allow-auto-statistics --auto-statistics all"
                        + " --allow-auto-metrics --auto-metrics all",
                image));
        docker(command.toArray(String[]::new));
        return container;
    }

    // the API of a client as the owner of its cohorts, who logs in with the API's own client
    private EndToEndApi clientApi(String container, String project, String clientUrl) {
        String keycloak = inner(container, project, "keycloak");
        String id = ProcessHelper.output("docker", "exec", container, "docker", "exec", keycloak, "/opt/keycloak/bin/kcadm.sh", "get",
                "clients", "--config", KCADM_CONFIG, "-r", "FLNet-Client", "-q", "clientId=local-learning-api", "--fields", "id",
                "--format", "csv", "--noquotes").orElse("").strip();
        assertFalse(id.isEmpty(), "Keycloak id of local-learning-api in " + container);
        kcadm(container, keycloak, "password login of local-learning-api", "/opt/keycloak/bin/kcadm.sh", "update", "clients/" + id,
                "--config", KCADM_CONFIG, "-r", "FLNet-Client", "-s", "directAccessGrantsEnabled=true");
        String secrets = ProcessHelper.output("docker", "exec", container, "sh", "-c",
                "cat /var/lib/flnet/clients/*/env/local-learning-secrets.env").orElse("");
        String secret = secrets.lines()
                .filter(line -> line.startsWith("QUARKUS_OIDC_CREDENTIALS_SECRET="))
                .map(line -> line.substring(line.indexOf('=') + 1).strip().replaceAll("^['\"]|['\"]$", ""))
                .findFirst()
                .orElseGet(() -> fail("client secret of local-learning-api in " + container));
        return EndToEndApi.client(http, clientUrl, secret, CLIENT_USER, clientPassword);
    }

    // The owner of a client's cohorts, as soon as the client's Keycloak answers: the client notifies it when it imports on start
    private void importClientUser(String container, String project, Instant deadline) throws Exception {
        String login = "/opt/keycloak/bin/kcadm.sh config credentials --config " + KCADM_CONFIG
                + " --server http://localhost:8080/auth --realm master"
                + " --user \"$KC_BOOTSTRAP_ADMIN_USERNAME\" --password \"$KC_BOOTSTRAP_ADMIN_PASSWORD\"";
        String keycloak = innerOrEmpty(container, project, "keycloak");
        while (keycloak.isEmpty() || ProcessHelper.output("docker", "exec", container, "docker", "exec", keycloak, "sh", "-c", login).isEmpty()) {
            assertTrue(Instant.now().isBefore(deadline), "Keycloak of " + container + " did not answer in time");
            Thread.sleep(2_000);
            keycloak = innerOrEmpty(container, project, "keycloak");
        }
        Map<String, Object> user = Map.of("username", CLIENT_USER, "enabled", true, "emailVerified", true,
                "firstName", "E2E", "lastName", CLIENT_USER, "email", CLIENT_USER + "@e2e.invalid",
                "realmRoles", List.of("Admin", "Data-Admin", "Data-Access-Manager"),
                "credentials", List.of(Map.of("type", "password", "value", clientPassword, "temporary", false)));
        String file = JSON.writeValueAsString(Map.of("ifResourceExists", "OVERWRITE", "users", List.of(user)));
        kcadm(container, keycloak, "import " + CLIENT_USER, "sh", "-c", "printf '%s' '" + file + "' > /tmp/e2e-user.json"
                + " && /opt/keycloak/bin/kcadm.sh create partialImport --config " + KCADM_CONFIG + " -r FLNet-Client -f /tmp/e2e-user.json");
    }

    // a user of a realm, created with the Keycloak admin CLI inside the deployment's Keycloak and its bootstrap admin
    private void createUser(String container, String project, String realm, String username, String password, String... roles) {
        String keycloak = inner(container, project, "keycloak");
        kcadm(container, keycloak, "login", "sh", "-c", "/opt/keycloak/bin/kcadm.sh config credentials --config " + KCADM_CONFIG
                + " --server http://localhost:8080/auth --realm master"
                + " --user \"$KC_BOOTSTRAP_ADMIN_USERNAME\" --password \"$KC_BOOTSTRAP_ADMIN_PASSWORD\"");
        kcadm(container, keycloak, "create user " + username, "/opt/keycloak/bin/kcadm.sh", "create", "users", "--config", KCADM_CONFIG,
                "-r", realm, "-s", "username=" + username, "-s", "enabled=true", "-s", "emailVerified=true", "-s", "firstName=E2E",
                "-s", "lastName=" + username, "-s", "email=" + username + "@e2e.invalid");
        kcadm(container, keycloak, "password of " + username, "/opt/keycloak/bin/kcadm.sh", "set-password", "--config", KCADM_CONFIG,
                "-r", realm, "--username", username, "--new-password", password);
        if (roles.length > 0) {
            List<String> addRoles = new ArrayList<>(List.of("/opt/keycloak/bin/kcadm.sh", "add-roles", "--config", KCADM_CONFIG,
                    "-r", realm, "--uusername", username));
            for (String role : roles) {
                addRoles.addAll(List.of("--rolename", role));
            }
            kcadm(container, keycloak, "roles of " + username, addRoles.toArray(String[]::new));
        }
    }

    private void kcadm(String container, String keycloak, String step, String... command) {
        List<String> exec = new ArrayList<>(List.of("docker", "exec", container, "docker", "exec", keycloak));
        exec.addAll(List.of(command));
        assertEquals(0, ProcessHelper.runInteractive(work, exec), "Keycloak admin CLI in " + container + ": " + step);
    }

    // the running container of a compose service inside a DinD container
    private static String inner(String container, String project, String service) {
        String inner = innerOrEmpty(container, project, service);
        assertFalse(inner.isEmpty() || inner.contains("\n"), "one running container of " + project + "/" + service + " in " + container);
        return inner;
    }

    private static String innerOrEmpty(String container, String project, String service) {
        return ProcessHelper.output("docker", "exec", container, "docker", "ps", "-q", "--filter", "label=com.docker.compose.project=" + project,
                "--filter", "label=com.docker.compose.service=" + service).orElse("").strip();
    }

    // docker run arguments every DinD container of this run shares; the caller adds its own and the image
    private List<String> dockerRun(String container, String image, Path composeExtra, String side) {
        List<String> command = new ArrayList<>(List.of("run", "-d", "--privileged", "--name", container, "--label", label(),
                "--network", run, "-e", "FLNET_IMAGE_TAG=" + System.getProperty("e2e.image-tag", DEFAULT_IMAGE_TAG),
                "-v", composeExtra + ":/compose-extra/e2e.yml:ro"));
        Optional.ofNullable(System.getProperty("e2e.docker-config")).filter(path -> !path.isBlank())
                .ifPresent(config -> command.addAll(List.of("-v", Path.of(config).toAbsolutePath() + ":/root/.docker/config.json:ro")));
        if (flnetBinary != null) {
            command.addAll(List.of("-v", flnetBinary + ":/usr/local/bin/flnet:ro"));
        }
        // a file of its own: compose merges it with the e2e one, a service in both would be one key twice
        Path overrides = work.resolve(side + "-images.yml");
        try {
            String services = imageOverrides("platform".equals(side) ? PLATFORM_SERVICES : CLIENT_SERVICES);
            Files.writeString(overrides, services.isEmpty() ? "services: {}\n" : "services:\n" + services);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        command.addAll(List.of("-v", overrides + ":/compose-extra/images.yml:ro"));
        Path images = work.resolve("images-" + side);
        if (Files.isDirectory(images)) {
            command.addAll(List.of("-v", images + ":/images:ro"));
        }
        return command;
    }

    // the DinD image of a role, pulled when e2e.dind-image-tag names published ones, else built from dind/
    private String dindImage(String role) {
        String published = System.getProperty("e2e.dind-image-tag", "");
        if (!published.isBlank()) {
            String image = "ghcr.io/fedlearnnet/flnet-" + role + "-dind:" + published;
            docker("pull", image);
            return image;
        }
        String image = "flnet-" + role + "-dind:e2e";
        // a mounted flnet needs none from a release
        String version = flnetBinary != null ? "none" : System.getProperty("e2e.flnet-version", DEFAULT_FLNET_VERSION);
        docker("build", "--build-arg", "FLNET_VERSION=" + version, "-f", "dind/" + role + "/Dockerfile", "-t", image, "dind");
        return image;
    }

    /**
     * The flnet the DinD containers use instead of the one of their image: e2e.flnet-binary, or with
     * e2e.flnet-from-source the native musl executable of this checkout, built like the release asset.
     */
    private static Path flnet() throws IOException {
        String binary = System.getProperty("e2e.flnet-binary", "");
        if (!binary.isBlank()) {
            return Path.of(binary).toAbsolutePath();
        }
        if (!Boolean.getBoolean("e2e.flnet-from-source")) {
            return null;
        }
        // a copy, so the build in the container leaves the target of this run alone
        Path source = work.resolve("flnet-source");
        Path checkout = Path.of("").toAbsolutePath();
        for (String entry : List.of("pom.xml", "mvnw", ".mvn", "lombok.config", "src")) {
            copyTree(checkout.resolve(entry), source.resolve(entry));
        }
        Path m2 = Path.of(System.getProperty("user.home"), ".m2");
        docker("run", "--rm", "-v", source + ":/src", "-v", m2 + ":/root/.m2", "-w", "/src", FLNET_BUILD_IMAGE,
                "./mvnw", "-B", "--no-transfer-progress", "package", "-Dnative", "-DskipTests");
        try (Stream<Path> files = Files.list(source.resolve("target"))) {
            Path runner = files.filter(file -> file.getFileName().toString().endsWith("-runner")).findFirst()
                    .orElseGet(() -> fail("no native flnet in " + source.resolve("target")));
            return Files.move(runner, work.resolve("flnet"));
        }
    }

    // the first /24 of 10.213.0.0/16 no network of the daemon has
    private static String freeSubnet() {
        List<String> networks = Arrays.stream(ProcessHelper.output("docker", "network", "ls", "-q").orElse("").split("\\s+"))
                .filter(id -> !id.isBlank())
                .toList();
        List<String> inspect = new ArrayList<>(List.of("docker", "network", "inspect", "--format", "{{range .IPAM.Config}}{{.Subnet}} {{end}}"));
        inspect.addAll(networks);
        String used = networks.isEmpty() ? "" : ProcessHelper.output(inspect.toArray(String[]::new)).orElse("");
        for (int third = 0; third < 256; third++) {
            String subnet = "10.213." + third + ".0/24";
            if (!used.contains(subnet)) {
                return subnet;
            }
        }
        return fail("no free /24 in 10.213.0.0/16");
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (Files.notExists(from)) {
            return;
        }
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : files.toList()) {
                Path target = to.resolve(from.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    /** Saves the e2e.local-images of a deployment's services, which its DinD container loads instead of pulling them. */
    private static void saveLocalImages(String side, Set<String> services) throws IOException {
        Map<String, String> local = images("e2e.local-images");
        local.keySet().retainAll(services);
        if (local.isEmpty()) {
            return;
        }
        Path images = Files.createDirectories(work.resolve("images-" + side));
        for (String image : Set.copyOf(local.values())) {
            docker("save", "-o", images.resolve(image.replaceAll("[^A-Za-z0-9.-]", "_") + ".tar").toString(), image);
        }
    }

    // e2e.images and e2e.local-images as compose services of one deployment; a service the deployment has not is left out
    private static String imageOverrides(Set<String> services) {
        StringBuilder overrides = new StringBuilder();
        images("e2e.images").forEach((service, image) -> {
            if (services.contains(service)) {
                overrides.append("  ").append(service).append(":\n    image: ").append(image).append("\n");
            }
        });
        images("e2e.local-images").forEach((service, image) -> {
            if (services.contains(service)) {
                overrides.append("  ").append(service).append(":\n    image: ").append(image).append("\n    pull_policy: never\n");
            }
        });
        return overrides.toString();
    }

    // service=image,... of a property
    private static Map<String, String> images(String property) {
        Map<String, String> images = new LinkedHashMap<>();
        Arrays.stream(System.getProperty(property, "").split(","))
                .map(String::strip)
                .filter(entry -> entry.contains("="))
                .map(entry -> entry.split("=", 2))
                .forEach(entry -> images.put(entry[0].strip(), entry[1].strip()));
        return images;
    }

    private static void docker(String... args) {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(args));
        assertEquals(0, ProcessHelper.runInteractive(Path.of("."), command), String.join(" ", command));
    }

    // into target/e2e-logs: surefire keeps little of what a forked test writes, and the containers are removed after
    private void diagnose(String container, String kind, String instance) {
        Path logs = Path.of("target", "e2e-logs");
        try {
            Files.createDirectories(logs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ProcessHelper.runToFile(work, List.of("docker", "inspect", "-f",
                "{{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}} {{.State.Error}}", container),
                logs.resolve(container + "-state.txt").toAbsolutePath());
        ProcessHelper.runToFile(work, List.of("docker", "logs", container), logs.resolve(container + ".log").toAbsolutePath());
        ProcessHelper.runToFile(work, List.of("docker", "exec", container, "flnet", kind, "status", "--no-input", "--name", instance),
                logs.resolve(container + "-status.txt").toAbsolutePath());
        System.err.println("Logs of " + container + " in " + logs.toAbsolutePath());
    }

    private String label() {
        return "flnet.e2e=" + run;
    }

    private String clinicContainer(int clinic) {
        return run + "-clinic-" + clinic;
    }

    private static String clinicUser(int clinic) {
        return "e2e-client-" + clinic;
    }

    // distinct patients of a clinic CSV in the raw format of the connector
    private static int patients(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        int column = Arrays.asList(lines.getFirst().split(",")).indexOf("patient_nbr");
        return (int) lines.stream().skip(1).map(line -> line.split(",")[column]).distinct().count();
    }

    // a fixture of src/test/resources/e2e as a file, e.g. to mount it
    private static Path fixture(String fixture) {
        Path file = work.resolve("fixtures").resolve(fixture);
        if (Files.notExists(file)) {
            try (InputStream in = EndToEndTest.class.getResourceAsStream("/e2e/" + fixture)) {
                assertNotNull(in, "fixture e2e/" + fixture);
                Files.createDirectories(file.getParent());
                Files.copy(in, file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return file;
    }

    private void awaitHealthy(String url, Predicate<HttpResponse<String>> healthy, Instant deadline) throws InterruptedException {
        String last = "no response";
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (healthy.test(response)) {
                    return;
                }
                last = response.statusCode() + " " + response.body();
            } catch (IOException e) {
                last = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            Thread.sleep(5_000);
        }
        fail(url + " did not become healthy in time. Last response: " + last);
    }

    // Quarkus health of a service inside a DinD container
    private void awaitInnerHealthy(String container, String project, String service, String path, Set<String> ignored, Instant deadline)
            throws InterruptedException {
        String last = "no response";
        while (Instant.now().isBefore(deadline)) {
            assertTrue(ProcessHelper.output("docker", "inspect", "-f", "{{.State.Running}}", container).orElse("").contains("true"),
                    container + " stopped, see its logs");
            String inner = innerOrEmpty(container, project, service);
            if (!inner.isEmpty()) {
                // without -f: a 503 still has the checks in the body
                last = ProcessHelper.output("docker", "exec", container, "docker", "exec", inner, "curl", "-sS", "http://localhost:8080" + path)
                        .orElse("curl failed");
                if (healthy(last, ignored)) {
                    return;
                }
            }
            Thread.sleep(5_000);
        }
        fail(project + "/" + service + " " + path + " in " + container + " did not become healthy in time. Last response: " + last);
    }

    private static boolean ok(HttpResponse<String> response) {
        return response.statusCode() == 200;
    }

    private static boolean notFound(HttpResponse<String> response) {
        return response.statusCode() == 404;
    }

    /**
     * A MicroProfile health response is healthy if every check (except the ignored ones) is UP. The overall
     * status is not used, so that an ignored check that is DOWN does not fail it.
     */
    private static boolean healthy(String body, Set<String> ignored) {
        try {
            JsonNode checks = JSON.readTree(body).path("checks");
            if (!checks.isArray() || checks.isEmpty()) {
                return false;
            }
            for (JsonNode check : checks) {
                if (!ignored.contains(check.path("name").asText()) && !"UP".equals(check.path("status").asText())) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false; // e.g. an error message while the service starts
        }
    }

    private static boolean absent(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false);
    }

    private static Set<Long> ids(JsonNode runs) {
        Set<Long> ids = new HashSet<>();
        runs.forEach(found -> ids.add(found.path("id").asLong()));
        return ids;
    }

    private static String secret() {
        return HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong()) + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
    }

    private static void leftovers(String what, List<String> command, List<String> failures) {
        String ids = ProcessHelper.output(command.toArray(String[]::new)).orElse("").strip();
        if (!ids.isEmpty()) {
            failures.add(what + " of the e2e run left: " + ids.replace('\n', ' '));
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
