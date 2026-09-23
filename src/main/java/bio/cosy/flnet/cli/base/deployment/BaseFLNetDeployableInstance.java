package bio.cosy.flnet.cli.base.deployment;

import bio.cosy.flnet.cli.base.BaseFLNet;
import bio.cosy.flnet.cli.base.env.CommonEnv;
import bio.cosy.flnet.cli.base.env.CommonSecretEnv;
import bio.cosy.flnet.cli.base.env.EnvVariable;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.EnvFileHelper;
import bio.cosy.flnet.cli.helper.SecretHelper;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.Range;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Getter
@Setter
public abstract class BaseFLNetDeployableInstance extends BaseFLNet {

    public static final String DEFAULT_NAME = "default";
    public static final String ORCH_SECRETS = "orch-secrets.env";
    public static final String KEYCLOAK_SECRETS = "keycloak-secrets.env";

    public static final String NAME_VARIABLE = CommonEnv.FLNET_INSTANCE_NAME.key();

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
    private String orchDbPassword;
    private String keycloakDbPassword;

    public abstract DeploymentKind getKind();

    @Override
    public String getTypeLabel() {
        return getKind().bundle();
    }

    public abstract List<@Range(min = 1, max = 65535, message = "Ports must be between 1 and 65535.") Integer> getPorts();

    public abstract String getAddress();


    public Path getEnvFile() {
        return resolve(".env");
    }

    public Path getComposeFile() {
        return resolve("docker-compose.yml");
    }

    public Path getSecretsDirectory() {
        return resolve("env");
    }

    public boolean isInitialized() {
        return getDirectory() != null && Files.isRegularFile(getEnvFile()) && Files.isRegularFile(getComposeFile());
    }

    public void requireInitialized() {
        if (!isInitialized()) {
            throw new CliException("No initialized " + getKind().displayName() + " found in " + getDirectory()
                    + ". Run 'flnet " + getKind().bundle() + " init' first.", CliException.ENVIRONMENT);
        }
    }

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


    public abstract Map<String, Object> toEnv();

    public abstract void fromEnv(Map<String, String> env);

    public abstract Map<String, Map<String, String>> toSecretFiles();

    public void fromSecretFiles(Map<String, Map<String, String>> files) {
        orchDbPassword = secretOrNull(files, ORCH_SECRETS, CommonSecretEnv.POSTGRES_PASSWORD.key());
        keycloakDbPassword = secretOrNull(files, KEYCLOAK_SECRETS, CommonSecretEnv.KC_DB_PASSWORD.key());
        keycloakAdminPassword = secretOrNull(files, KEYCLOAK_SECRETS, CommonSecretEnv.KC_BOOTSTRAP_ADMIN_PASSWORD.key());
        String admin = secretOrNull(files, KEYCLOAK_SECRETS, CommonSecretEnv.KC_BOOTSTRAP_ADMIN_USERNAME.key());
        if (admin != null) {
            keycloakAdminUsername = admin;
        }
    }

    public void generateSecrets(int length, int adminPasswordLength) {
        orchDbPassword = SecretHelper.generate(length);
        keycloakDbPassword = SecretHelper.generate(length);
        // Typed by the admin on first login, so shorter.
        keycloakAdminPassword = SecretHelper.generate(adminPasswordLength);
    }

    @AssertTrue(message = "Secrets are missing; generate them before saving.")
    public boolean hasSecrets() {
        return orchDbPassword != null && keycloakDbPassword != null && keycloakAdminPassword != null;
    }

    public abstract List<EnvVariable> getEnvVariables();

    public Map<String, String> envComments() {
        Map<String, String> comments = new LinkedHashMap<>();
        getEnvVariables().forEach(variable -> comments.put(variable.key(), variable.description()));
        return comments;
    }

    public Set<String> getSecretFileNames() {
        return toSecretFiles().keySet();
    }

    protected void putCommonEnv(Map<String, Object> env) {
        put(env, CommonEnv.COMPOSE_PROJECT_NAME, projectName);
        put(env, CommonEnv.FLNET_INSTANCE_NAME, getName());
    }

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

    public void load() {
        fromEnv(EnvFileHelper.read(getEnvFile()));
        Map<String, Map<String, String>> secrets = new LinkedHashMap<>();
        for (String file : getSecretFileNames()) {
            secrets.put(file, EnvFileHelper.read(getSecretsDirectory().resolve(file)));
        }
        fromSecretFiles(secrets);
    }


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


    protected static void put(Map<String, Object> env, EnvVariable variable, Object value) {
        env.put(variable.key(), value);
    }

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

    protected static int portOf(String hostAndPort, int fallback) {
        return hostAndPort == null ? fallback : intOr(hostAndPort.substring(hostAndPort.lastIndexOf(':') + 1), fallback);
    }

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
