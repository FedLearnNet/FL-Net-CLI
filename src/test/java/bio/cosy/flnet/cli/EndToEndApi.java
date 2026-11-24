package bio.cosy.flnet.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The REST API of an end-to-end platform or client behind its nginx, called as a user with a token from the
 * Keycloak of the deployment (password grant).
 */
class EndToEndApi {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);

    private final HttpClient http;
    private final String baseUrl;
    private final String apiPath;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final String username;
    private final String password;

    private String token;
    private Instant tokenExpiry = Instant.MIN;

    /** The global learning API under /api, logged in with the public client of the frontend. */
    static EndToEndApi platform(HttpClient http, String platformUrl, String username, String password) {
        return new EndToEndApi(http, platformUrl, "/api", "FLNet-Platform", "fl-net-clients", null, username, password);
    }

    /** The local learning API under /local-learning-api, logged in with its own confidential client. */
    static EndToEndApi client(HttpClient http, String clientUrl, String clientSecret, String username, String password) {
        return new EndToEndApi(http, clientUrl, "/local-learning-api", "FLNet-Client", "local-learning-api", clientSecret, username, password);
    }

    private EndToEndApi(HttpClient http, String baseUrl, String apiPath, String realm, String clientId, String clientSecret,
                        String username, String password) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.apiPath = apiPath;
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.username = username;
        this.password = password;
    }

    JsonNode get(String path) {
        return json(send(request(path).GET(), path));
    }

    // a download, e.g. a CSV
    String getText(String path) {
        return send(request(path).GET().header("Accept", "*/*"), path);
    }

    JsonNode post(String path, Object body) {
        return json(send(request(path).POST(body(body)).header("Content-Type", "application/json"), path));
    }

    JsonNode put(String path, Object body) {
        return json(send(request(path).PUT(body(body)).header("Content-Type", "application/json"), path));
    }

    // null while the platform does not know it (yet)
    JsonNode find(String path) {
        HttpResponse<String> response = exchange(request(path).GET().build());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() / 100 != 2) {
            fail("GET " + path + " returned " + response.statusCode() + ": " + response.body());
        }
        return json(response.body());
    }

    JsonNode upload(String path, Path file) {
        String boundary = "e2e" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + file.getFileName()
                + "\"\r\nContent-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        try {
            HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(head),
                    HttpRequest.BodyPublishers.ofFile(file), HttpRequest.BodyPublishers.ofByteArray(tail));
            return json(send(request(path).POST(body).header("Content-Type", "multipart/form-data; boundary=" + boundary), path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // A post whose response streams as long as what it started runs, e.g. a JSON array of a run's updates; not awaited
    CompletableFuture<HttpResponse<Void>> postInBackground(String path, Object body) {
        HttpRequest request = request(path).POST(body(body)).header("Content-Type", "application/json")
                .timeout(Duration.ofHours(2)).build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
    }

    /**
     * Posts to an endpoint that starts a run and streams it as server-sent events, each the whole run, and returns
     * the first event. The stream is closed then, the run goes on.
     */
    JsonNode postFirstEvent(String path, Object body, Duration timeout) {
        return postEvent(path, body, event -> true, timeout);
    }

    // the first event of the stream the predicate accepts, e.g. the first that names the run
    JsonNode postEvent(String path, Object body, Predicate<JsonNode> wanted, Duration timeout) {
        return event(request(path).POST(body(body)).header("Content-Type", "application/json"), path, wanted, timeout);
    }

    JsonNode getEvent(String path, Predicate<JsonNode> wanted, Duration timeout) {
        return event(request(path).GET(), path, wanted, timeout);
    }

    private JsonNode event(HttpRequest.Builder builder, String path, Predicate<JsonNode> wanted, Duration timeout) {
        HttpRequest request = builder.header("Accept", "text/event-stream").timeout(timeout).build();
        CompletableFuture<HttpResponse<InputStream>> response = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream stream = response.get(timeout.toSeconds(), TimeUnit.SECONDS).body()) {
            CompletableFuture<JsonNode> event = CompletableFuture.supplyAsync(() -> matchingEvent(stream, wanted));
            try {
                return event.get(timeout.toSeconds(), TimeUnit.SECONDS);
            } finally {
                stream.close(); // also ends a read still waiting
            }
        } catch (TimeoutException e) {
            response.cancel(true);
            return fail("No matching event from " + path + " within " + timeout);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ExecutionException e) {
            return fail("Stream of " + path + " failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail("Interrupted while reading " + path);
        }
    }

    /** Connects to the test embed socket of the app as its developer's frontend does. */
    TestEmbed testEmbed(long appId) {
        URI uri = URI.create(baseUrl.replaceFirst("^http", "ws") + apiPath + "/testembed/" + appId + "/CLIENT");
        TestEmbed listener = new TestEmbed();
        try {
            listener.socket = http.newWebSocketBuilder().header("Authorization", "Bearer " + token())
                    .connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, listener).get(30, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            fail("Test embed socket of app " + appId + " did not open", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted while connecting to the test embed socket");
        }
        return listener;
    }

    static final class TestEmbed implements WebSocket.Listener, AutoCloseable {

        private WebSocket socket;

        void send(String type, Object message, String runType) {
            try {
                String text = JSON.writeValueAsString(Map.of("type", type, "message", message, "runType", runType));
                socket.sendText(text, true).get(30, TimeUnit.SECONDS);
            } catch (IOException | ExecutionException | TimeoutException e) {
                fail("Could not send " + type + " over the test embed socket", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while sending " + type);
            }
        }

        // the platform's answers are not needed, the runs are followed over REST
        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            webSocket.request(1);
            return null;
        }

        @Override
        public void close() {
            if (socket != null && !socket.isOutputClosed()) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            }
        }
    }

    private static JsonNode matchingEvent(InputStream stream, Predicate<JsonNode> wanted) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        try {
            for (String line = reader.readLine(); ; line = reader.readLine()) {
                if (line != null && line.startsWith("data:")) {
                    data.append(line.substring(5).strip());
                } else if ((line == null || line.isEmpty()) && !data.isEmpty()) {
                    JsonNode event = JSON.readTree(data.toString());
                    if (wanted.test(event)) {
                        return event;
                    }
                    data.setLength(0);
                }
                if (line == null) {
                    throw new IllegalStateException("The stream ended without a matching event");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + apiPath + path)).timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token());
    }

    private String send(HttpRequest.Builder request, String path) {
        HttpResponse<String> response = exchange(request.build());
        if (response.statusCode() / 100 != 2) {
            fail(response.request().method() + " " + path + " returned " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private HttpResponse<String> exchange(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    // A fresh token shortly before the last one expires; the scenarios run longer than a token lives
    private synchronized String token() {
        if (Instant.now().isBefore(tokenExpiry)) {
            return token;
        }
        String form = "grant_type=password&scope=openid&client_id=" + clientId
                + (clientSecret == null ? "" : "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8))
                + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/auth/realms/" + realm + "/protocol/openid-connect/token"))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> response = exchange(request);
        if (response.statusCode() != 200) {
            fail("No token for " + username + ": " + response.statusCode() + " " + response.body());
        }
        JsonNode body = json(response.body());
        token = body.path("access_token").asText();
        tokenExpiry = Instant.now().plusSeconds(Math.max(10, body.path("expires_in").asLong(60) - 30));
        return token;
    }

    private static HttpRequest.BodyPublisher body(Object body) {
        try {
            return HttpRequest.BodyPublishers.ofString(body instanceof String text ? text : JSON.writeValueAsString(body));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode json(String body) {
        try {
            return body == null || body.isBlank() ? JSON.nullNode() : JSON.readTree(body);
        } catch (IOException e) {
            return fail("Not JSON: " + body);
        }
    }
}
