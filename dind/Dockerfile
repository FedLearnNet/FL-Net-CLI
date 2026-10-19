FROM docker:29.9.0-dind-alpine3.24
ARG TARGETARCH
ARG FLNET_VERSION=latest
ARG IMAGE_TAG=latest

ENV DOCKER_TLS_CERTDIR="" \
    FLNET_HOME=/var/lib/flnet \
    FLNET_IMAGE_TAG=${IMAGE_TAG}

ADD --chmod=755 https://github.com/FedLearnNet/FL-Net-CLI/releases/download/${FLNET_VERSION}/flnet-linux-${TARGETARCH}-musl /usr/local/bin/flnet
COPY --chmod=755 entrypoint.sh /opt/flnet-dind/entrypoint.sh
COPY docker-compose.dind.yml /opt/flnet-dind/docker-compose.dind.yml

EXPOSE 80
ENTRYPOINT ["/opt/flnet-dind/entrypoint.sh"]
