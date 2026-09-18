package bio.cosy.flnet.cli.base;

import bio.cosy.flnet.cli.support.CliException;
import bio.cosy.flnet.cli.support.EnvFile;
import bio.cosy.flnet.cli.support.Port;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A docker compose deployment (platform or client) set up on this machine. Holds every setting and
 * secret; {@link #toEnv()}/{@link #toSecretFiles()} produce the files docker compose reads and
 * {@link #fromEnv}/{@link #fromSecretFiles} restore an existing deployment from them.
 */
@Getter
@Setter
public abstract class BaseFLNetDeployableInstance extends BaseFLNet {

    /** Name of the first instance; it keeps the configured compose project name without suffix. */
    public static final String DEFAULT_NAME = "default";

    /** Stored in .env so an instance outside the instances home still knows its name. */
    public static final String NAME_VARIABLE = CommonEnv.FLNET_INSTANCE_NAME.key();

    /** Lowercase letters, digits and dashes: valid in docker compose project and container names. */
    private static final String NAME_REGEX = "[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?";
    private static final String NAME_RULE = "Use 1-32 lowercase letters, digits and dashes, starting and ending with a letter or digit (e.g. 'site-a').";

    @NotBlank(message = "The compose project name is required.")
    private String projectName;
    private String imageTag;
    @NotBlank(message = "The frontend image is required.")
    private String frontendImage;
    private boolean sslEnabled;
    private Path sslCertificate;
    private Path sslPrivateKey;
    private String keycloakAdminUsername;
    private String keycloakAdminPassword;

    public abstract DeploymentKind getKind();

    @Override
    public String getTypeLabel() {
        return getKind().bundle();
    }

    /** Host ports this deployment publishes. */
    public abstract List<@Port Integer> getPorts();

    /** Where users reach it. */
    public abstract String getAddress();

    // ---------------------------------------------------------------- files

    public Path getEnvFile() {
        return resolve(".env");
    }

    public Path getComposeFile() {
        return resolve("docker-compose.yml");
    }

    public Path getSecretsDirectory() {
        return resolve("env");
    }

    /** Whether {@code init} has completed for this directory. */
    public boolean isInitialized() {
        return getDirectory() != null && Files.isRegularFile(getEnvFile()) && Files.isRegularFile(getComposeFile());
    }

    public void requireInitialized() {
        if (!isInitialized()) {
            throw new CliException("No initialized " + getKind().displayName() + " found in " + getDirectory()
                    + ". Run 'flnet " + getKind().bundle() + " init' first.", CliException.ENVIRONMENT);
        }
    }

    /** Certificate files that SSL needs but that do not exist (yet). */
    public List<Path> getMissingCertificates() {
        List<Path> missing = new ArrayList<>();
        if (sslEnabled) {
            for (Path file : new Path[]{sslCertificate, sslPrivateKey}) {
                if (file != null && !Files.isRegularFile(file)) {
                    missing.add(file);
                }
            }
        }
        return missing;
    }

    // ---------------------------------------------------------------- serialization

    /** Contents of {@code .env}, in file order. */
    public abstract Map<String, Object> toEnv();

    /** Restores all settings from a {@code .env}. */
    public abstract void fromEnv(Map<String, String> env);

    /** Secret files below {@code env/}: file name to variables. */
    public abstract Map<String, Map<String, String>> toSecretFiles();

    /** Restores the secrets; {@code files} maps file names to their variables. */
    public abstract void fromSecretFiles(Map<String, Map<String, String>> files);

    /** Creates every generated secret (database passwords, client secrets, admin password). */
    public abstract void generateSecrets(int length, int adminPasswordLength);

    /** Whether all generated secrets are present, i.e. the deployment can keep its data. */
    @AssertTrue(message = "Secrets are missing; generate them before saving.")
    public abstract boolean hasSecrets();

    /** Every variable of this deployment's .env, common ones first. */
    public abstract List<EnvVariable> getEnvVariables();

    /** Comments written above the .env variables: what each one means. */
    public Map<String, String> envComments() {
        Map<String, String> comments = new LinkedHashMap<>();
        getEnvVariables().forEach(variable -> comments.put(variable.key(), variable.description()));
        return comments;
    }

    /** Names of the secret files this deployment writes. */
    public Set<String> getSecretFileNames() {
        return toSecretFiles().keySet();
    }

    /** Variables every deployment writes; subclasses put them where their .env expects them. */
    protected void putCommonEnv(Map<String, Object> env) {
        put(env, CommonEnv.COMPOSE_PROJECT_NAME, projectName);
        put(env, CommonEnv.FLNET_INSTANCE_NAME, getName());
    }

    /** SSL variables; {@code placeholder} replaces unset certificate paths. */
    protected void putSslEnv(Map<String, Object> env, Object placeholder) {
        put(env, CommonEnv.COMPOSE_PROFILES, sslEnabled ? "ssl" : "no-ssl");
        put(env, CommonEnv.SSL_CERT_PUBLIC_KEY, sslCertificate == null ? placeholder : sslCertificate);
        put(env, CommonEnv.SSL_CERT_PRIVATE_KEY, sslPrivateKey == null ? placeholder : sslPrivateKey);
    }

    protected void readCommonEnv(Map<String, String> env) {
        projectName = CommonEnv.COMPOSE_PROJECT_NAME.in(env, projectName);
        if (CommonEnv.FLNET_INSTANCE_NAME.in(env) != null) {
            setName(CommonEnv.FLNET_INSTANCE_NAME.in(env));
        }
        frontendImage = CommonEnv.FRONTEND_IMAGE.in(env, frontendImage);
        sslEnabled = "ssl".equals(CommonEnv.COMPOSE_PROFILES.in(env));
        sslCertificate = pathOrNull(CommonEnv.SSL_CERT_PUBLIC_KEY.in(env));
        sslPrivateKey = pathOrNull(CommonEnv.SSL_CERT_PRIVATE_KEY.in(env));
    }

    /** Reads this deployment's .env and secret files (missing files are empty). */
    public void load() {
        fromEnv(EnvFile.read(getEnvFile()));
        Map<String, Map<String, String>> secrets = new LinkedHashMap<>();
        for (String file : getSecretFileNames()) {
            secrets.put(file, EnvFile.read(getSecretsDirectory().resolve(file)));
        }
        fromSecretFiles(secrets);
    }

    // ---------------------------------------------------------------- validation

    /** Validator for prompts; the same rule as the constraint on {@link #getName()}. */
    public static String validateName(String name) {
        return name != null && name.matches(NAME_REGEX) ? null : NAME_RULE;
    }

    @Override
    @Pattern(regexp = NAME_REGEX, message = "Invalid instance name. " + NAME_RULE)
    public String getName() {
        return super.getName();
    }

    @AssertTrue(message = "Each port can only be used once.")
    boolean isPortsDistinct() {
        return new HashSet<>(getPorts()).size() == getPorts().size();
    }

    @AssertTrue(message = "SSL is enabled but the certificate or private key is not set.")
    boolean isSslFilesSet() {
        return !sslEnabled || (sslCertificate != null && sslPrivateKey != null);
    }

    // ---------------------------------------------------------------- helpers

    protected static void put(Map<String, Object> env, EnvVariable variable, Object value) {
        env.put(variable.key(), value);
    }

    /** {@link CommonEnv} followed by the deployment's own variables. */
    protected static List<EnvVariable> envVariables(EnvVariable[] own) {
        List<EnvVariable> all = new ArrayList<>(List.of(CommonEnv.values()));
        all.addAll(List.of(own));
        return all;
    }

    protected static Path pathOrNull(String value) {
        return value == null || value.isBlank() || "dummyfile".equals(value) ? null : Path.of(value);
    }

    protected static boolean bool(String value, boolean fallback) {
        return value == null ? fallback : value.strip().equalsIgnoreCase("true");
    }

    protected static int intOr(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** "127.0.0.1:8250" or "8250" -> 8250 */
    protected static int portOf(String hostAndPort, int fallback) {
        return hostAndPort == null ? fallback : intOr(hostAndPort.substring(hostAndPort.lastIndexOf(':') + 1), fallback);
    }

    /** "127.0.0.1:8250" -> "127.0.0.1" */
    protected static String hostOf(String hostAndPort, String fallback) {
        return hostAndPort == null || !hostAndPort.contains(":") ? fallback : hostAndPort.substring(0, hostAndPort.lastIndexOf(':'));
    }

    protected static Map<String, String> ordered(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    protected static String secretOrNull(Map<String, Map<String, String>> files, String file, String key) {
        String value = files.getOrDefault(file, Map.of()).get(key);
        return value == null || value.isBlank() ? null : value;
    }

    public void setSslCertificate(Path sslCertificate) {
        this.sslCertificate = sslCertificate == null ? null : sslCertificate.toAbsolutePath().normalize();
    }

    public void setSslPrivateKey(Path sslPrivateKey) {
        this.sslPrivateKey = sslPrivateKey == null ? null : sslPrivateKey.toAbsolutePath().normalize();
    }
}
