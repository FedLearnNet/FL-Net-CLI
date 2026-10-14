package bio.cosy.flnet.cli.base.env;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum CommonEnv implements EnvVariable {
    COMPOSE_PROJECT_NAME("docker compose project: prefix of all containers, volumes and networks of this deployment"),
    FLNET_INSTANCE_NAME("Name of this instance in flnet (--name)"),
    IMAGE_TAG("Tag of the FL-Net images: 'latest' (main), 'staging' (develop) or a release version"),
    FRONTEND_IMAGE("Docker image of the web frontend"),
    TOOL_REGISTRY("host[:port] of the self-hosted registry tools are pulled from without login; empty = GitLab registry only"),
    COMPOSE_PROFILES("'ssl' = nginx terminates SSL with the certificate below, 'no-ssl' = plain HTTP (e.g. behind a reverse proxy)"),
    SSL_CERT_PUBLIC_KEY("SSL certificate (full chain, PEM) used by nginx"),
    SSL_CERT_PRIVATE_KEY("Private key (PEM) of the SSL certificate");

    private final String description;
}
