package bio.cosy.flnet.cli.update;

import bio.cosy.flnet.cli.config.FLNetCliConfig;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.ReleaseVersion;
import bio.cosy.flnet.cli.helper.VersionProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class UpdateBO {

    @Inject
    FLNetCliConfig config;

    @Inject
    ObjectMapper objectMapper;

    public record Release(ReleaseVersion version, String url) {
    }


    public static Optional<Release> newer(List<Release> releases, Optional<ReleaseVersion> current, boolean includeCandidates) {
        boolean candidates = includeCandidates || current.map(v -> !v.isRelease()).orElse(false);
        return releases.stream()
                .filter(release -> candidates || release.version().isRelease())
                .filter(release -> current.map(v -> release.version().compareTo(v) > 0).orElse(true))
                .max(Comparator.comparing(Release::version));
    }

    public List<Release> releases() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.update().releasesUrl() + "?per_page=100"))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "flnet/" + VersionProvider.version())
                .GET()
                .build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new CliException("Could not read the releases from " + config.update().releasesUrl()
                        + " (HTTP " + response.statusCode() + ").", CliException.ENVIRONMENT);
            }
            return parse(objectMapper.readTree(response.body()));
        } catch (IOException e) {
            throw new CliException("Could not reach " + config.update().releasesUrl() + ": " + e.getMessage(),
                    CliException.ENVIRONMENT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CliException.aborted();
        }
    }

    static List<Release> parse(JsonNode json) {
        List<Release> releases = new ArrayList<>();
        for (JsonNode node : json) {
            if (node.path("draft").asBoolean(false)) {
                continue;
            }
            ReleaseVersion.parse(node.path("tag_name").asText(null))
                    .ifPresent(version -> releases.add(new Release(version, node.path("html_url").asText(""))));
        }
        return releases;
    }
}
