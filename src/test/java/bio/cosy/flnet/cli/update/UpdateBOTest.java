package bio.cosy.flnet.cli.update;

import bio.cosy.flnet.cli.helper.ReleaseVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class UpdateBOTest {

    private static final List<UpdateBO.Release> RELEASES = UpdateBO.parse(json("""
            [
              {"tag_name": "latest", "prerelease": true, "html_url": "u/latest"},
              {"tag_name": "1.1.0.rc0", "prerelease": true, "html_url": "u/1.1.0.rc0"},
              {"tag_name": "1.0.1", "html_url": "u/1.0.1"},
              {"tag_name": "1.2.0", "draft": true, "html_url": "u/draft"},
              {"tag_name": "1.0.0", "html_url": "u/1.0.0"},
              {"tag_name": "0.0.2", "html_url": "u/0.0.2"}
            ]
            """));

    @Test
    void skipsDraftsAndRollingBuild() {
        assertEquals(List.of("1.1.0.rc0", "1.0.1", "1.0.0", "0.0.2"),
                RELEASES.stream().map(r -> r.version().toString()).toList());
    }

    @Test
    void releaseOnlyGetsReleases() {
        assertEquals("1.0.1", newer("1.0.0", false));
        assertNull(newer("1.0.1", false));
    }

    @Test
    void candidatesOnRequestOrWhenOnACandidate() {
        assertEquals("1.1.0.rc0", newer("1.0.1", true));
        assertEquals("1.1.0.rc0", newer("1.0.0.rc0", false));
        assertNull(newer("1.1.0.rc0", false));
    }

    @Test
    void unknownVersionGetsNewestRelease() {
        assertEquals("1.0.1", newer("1.0.0-SNAPSHOT", false));
    }

    @Test
    void ordersVersions() {
        List<String> ordered = List.of("1.0.0.dev", "1.0.0.rc0", "1.0.0.rc1", "1.0.0.rc10", "1.0.0", "1.0.1", "1.1.0.dev", "1.10.0");
        for (int i = 1; i < ordered.size(); i++) {
            assertTrue(ReleaseVersion.parse(ordered.get(i)).orElseThrow()
                    .compareTo(ReleaseVersion.parse(ordered.get(i - 1)).orElseThrow()) > 0, ordered.get(i));
        }
        assertEquals("1.0.0", ReleaseVersion.parse("v1.0.0").orElseThrow().toString());
    }

    private static String newer(String current, boolean candidates) {
        return UpdateBO.newer(RELEASES, ReleaseVersion.parse(current), candidates)
                .map(r -> r.version().toString())
                .orElse(null);
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String text) {
        try {
            return new ObjectMapper().readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
