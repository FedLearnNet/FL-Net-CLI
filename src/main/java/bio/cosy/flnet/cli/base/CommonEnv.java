package bio.cosy.flnet.cli.base;

/** {@code .env} variables of both platforms and clients. */
public enum CommonEnv implements EnvVariable {
    COMPOSE_PROJECT_NAME("docker compose project: prefix of all containers, volumes and networks of this deployment"),
    FLNET_INSTANCE_NAME("Name of this instance in flnet (--name)"),
    FRONTEND_IMAGE("Docker image of the web frontend"),
    COMPOSE_PROFILES("'ssl' = nginx terminates SSL with the certificate below, 'no-ssl' = plain HTTP (e.g. behind a reverse proxy)"),
    SSL_CERT_PUBLIC_KEY("SSL certificate (full chain, PEM) used by nginx"),
    SSL_CERT_PRIVATE_KEY("Private key (PEM) of the SSL certificate");

    private final String description;

    CommonEnv(String description) {
        this.description = description;
    }

    @Override
    public String description() {
        return description;
    }
}
