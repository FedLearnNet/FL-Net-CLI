package bio.cosy.flnet.cli;

import bio.cosy.flnet.cli.helper.EnvFileHelper;
import bio.cosy.flnet.cli.helper.ProcessHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Sets up a real platform and a client with the CLI, pulls and starts them, waits until their health endpoints
 * report UP and removes everything again. Needs docker with the compose plugin, openssl and access to the image
 * registries (private ghcr.io packages need {@code docker login ghcr.io}); takes several minutes.
 *
 * <pre>./mvnw test -Pe2e</pre>
 * <p>
 * {@code -De2e.timeout-minutes=<n>} changes how long the services may take to become healthy (default 15),
 * {@code -De2e.image-tag=<tag>} the images under test (default {@value #DEFAULT_IMAGE_TAG}, i.e. the develop builds;
 * {@code latest} for main).
 */
@QuarkusMainTest
@Tag("e2e")
class EndToEndTest {

    /**
     * Readiness checks that cannot be UP in this setup: the client runs without federation, so its WebSocket
     * to the platform is never connected.
     */
    private static final Set<String> CLIENT_READINESS_IGNORED = Set.of("websocket-client-readiness");

    private static final String DEFAULT_IMAGE_TAG = "staging";

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    @TempDir
    Path certificates;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    // random so the docker compose projects never collide with a real instance or a parallel run
    private final String name = "e2e-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt()).substring(0, 6);

    private QuarkusMainLauncher launcher;
    private boolean passed;

    @BeforeEach
    void isolateHome() {
        System.setProperty("flnet.home", home.toString());
    }

    @Test
    void setsUpPullsStartsAndCleansPlatformAndClient(QuarkusMainLauncher launcher) throws Exception {
        assumeTrue(ProcessHelper.probe("docker", "compose", "version").isPresent(), "docker compose required");
        assumeTrue(ProcessHelper.probe("docker", "info", "--format", "{{.ServerVersion}}").isPresent(), "running docker daemon required");
        assumeTrue(ProcessHelper.probe("openssl", "version").isPresent(), "openssl required");
        this.launcher = launcher;

        int platformPort = freePort();
        int relayPort = freePort();
        int clientPort = freePort();
        Path cert = certificates.resolve("fullchain.pem");
        Path key = certificates.resolve("privkey.pem");
        assertEquals(0, ProcessHelper.runInteractive(certificates, List.of("openssl", "req", "-x509", "-newkey", "rsa:2048",
                "-nodes", "-days", "1", "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost",
                "-keyout", key.toString(), "-out", cert.toString())), "self-signed certificate for the relay");

        String imageTag = System.getProperty("e2e.image-tag", DEFAULT_IMAGE_TAG);
        String platformUrl = "http://localhost:" + platformPort;
        String clientUrl = "http://localhost:" + clientPort;

        // 1. set up
        flnet("platform", "init", "--no-input", "--yes", "--name", name, "--domain", platformUrl, "--port", String.valueOf(platformPort),
                "--relay-port", String.valueOf(relayPort), "--ssl-cert", cert.toString(), "--ssl-key", key.toString(),
                "--no-nginx-ssl", "--no-client-auth", "--min-clients", "1", "--image-tag", imageTag);
        flnet("client", "init", "--no-input", "--yes", "--name", name, "--network", "custom", "--platform-url", platformUrl,
                "--platform-relay-port", String.valueOf(relayPort), "--no-platform-auth", "--no-federation",
                "--listen", "localhost", "--port", String.valueOf(clientPort), "--image-tag", imageTag);
        Path platformDir = home.resolve("platforms").resolve(name);
        Path clientDir = home.resolve("clients").resolve(name);
        assertEquals("127.0.0.1:" + platformPort, EnvFileHelper.read(platformDir.resolve(".env")).get("NGINX_PORT"));
        assertEquals(String.valueOf(clientPort), EnvFileHelper.read(clientDir.resolve(".env")).get("EXPOSED_PORT"));
        assertEquals(imageTag, EnvFileHelper.read(platformDir.resolve(".env")).get("IMAGE_TAG"));
        assertEquals(imageTag, EnvFileHelper.read(clientDir.resolve(".env")).get("IMAGE_TAG"));

        // 2. pull (nothing runs yet, so nothing is restarted) and start
        flnet("platform", "pull", "--no-input", "--name", name);
        flnet("client", "pull", "--no-input", "--name", name);
        flnet("platform", "up", "--no-input", "--name", name);
        flnet("client", "up", "--no-input", "--name", name);

        // 3. health: everything behind the nginx of each deployment
        Instant deadline = Instant.now().plus(Duration.ofMinutes(Long.getLong("e2e.timeout-minutes", 15)));
        Map<String, Predicate<HttpResponse<String>>> checks = new LinkedHashMap<>();
        checks.put(platformUrl + "/auth/realms/FLNet-Platform", EndToEndTest::ok);
        checks.put(platformUrl + "/data-modeler/q/health", response -> healthy(response, Set.of()));
        checks.put(platformUrl + "/api/q/health", response -> healthy(response, Set.of()));
        checks.put(platformUrl + "/", EndToEndTest::ok);
        checks.put(clientUrl + "/auth/realms/FLNet-Client", EndToEndTest::ok);
        checks.put(clientUrl + "/local-learning-api/q/health/live", response -> healthy(response, Set.of()));
        checks.put(clientUrl + "/local-learning-api/q/health/ready", response -> healthy(response, CLIENT_READINESS_IGNORED));
        checks.put(clientUrl + "/", EndToEndTest::ok);
        for (var check : checks.entrySet()) {
            awaitHealthy(check.getKey(), check.getValue(), deadline);
        }

        // both show up as running instances
        LaunchResult list = flnet("list");
        assertTrue(list.getOutput().contains(name), list.getOutput());
        passed = true;
    }

    @AfterEach
    void cleanUp() {
        try {
            if (launcher != null) { // null: skipped before anything was created
                removeInstances();
            }
        } finally {
            System.clearProperty("flnet.home");
        }
    }

    private void removeInstances() {
        List<String> failures = new ArrayList<>();
        for (String kind : List.of("client", "platform")) {
            if (!Files.isDirectory(home.resolve(kind + "s").resolve(name))) {
                continue;
            }
            if (!passed) {
                launcher.launch(kind, "status", "--no-input", "--name", name);
                launcher.launch(kind, "logs", "--no-input", "--name", name, "--tail", "80");
            }
            LaunchResult clean = launcher.launch(kind, "clean", "--no-input", "--yes", "--purge", "--name", name);
            if (clean.exitCode() != 0) {
                failures.add(kind + " clean: " + clean.getErrorOutput());
            }
            String project = "fl-net-" + kind + "-" + name;
            String label = "label=com.docker.compose.project=" + project;
            leftovers("containers", project, List.of("docker", "ps", "-aq", "--filter", label), failures);
            leftovers("volumes", project, List.of("docker", "volume", "ls", "-q", "--filter", label), failures);
            leftovers("networks", project, List.of("docker", "network", "ls", "-q", "--filter", label), failures);
            if (Files.exists(home.resolve(kind + "s").resolve(name))) {
                failures.add(kind + " directory still exists after --purge");
            }
        }
        if (!failures.isEmpty()) {
            fail("Clean up incomplete:\n" + String.join("\n", failures));
        }
    }

    private LaunchResult flnet(String... args) {
        LaunchResult result = launcher.launch(args);
        assertEquals(0, result.exitCode(), "flnet " + String.join(" ", args) + "\n" + result.getOutput() + "\n" + result.getErrorOutput());
        return result;
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

    private static boolean ok(HttpResponse<String> response) {
        return response.statusCode() == 200;
    }

    /**
     * A MicroProfile health response is healthy if every check (except the ignored ones) is UP. The overall
     * status is not used, so that an ignored check that is DOWN does not fail it.
     */
    private static boolean healthy(HttpResponse<String> response, Set<String> ignored) {
        try {
            JsonNode checks = JSON.readTree(response.body()).path("checks");
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
            return false; // e.g. the nginx error page while the service starts
        }
    }

    private static void leftovers(String what, String project, List<String> command, List<String> failures) {
        String ids = ProcessHelper.output(command.toArray(String[]::new)).orElse("").strip();
        if (!ids.isEmpty()) {
            failures.add(what + " of " + project + " left: " + ids.replace('\n', ' '));
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
