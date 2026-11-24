FROM docker:29.9.0-dind-alpine3.24
ARG TARGETARCH
ARG FLNET_VERSION=latest
ARG IMAGE_TAG=latest

ENV DOCKER_TLS_CERTDIR="" \
    FLNET_HOME=/var/lib/flnet \
    FLNET_IMAGE_TAG=${IMAGE_TAG}

# openssl for a self-signed relay certificate, socat to expose the nginx bound to 127.0.0.1
RUN apk add --no-cache openssl socat

# Build context: dind/ (docker build -f dind/platform/Dockerfile dind)
# FLNET_VERSION=none: no flnet in the image, one is mounted to /usr/local/bin/flnet, e.g. built from a local branch
RUN if [ "$FLNET_VERSION" != none ]; then \
      wget -qO /usr/local/bin/flnet "https://github.com/FedLearnNet/FL-Net-CLI/releases/download/${FLNET_VERSION}/flnet-linux-${TARGETARCH}-musl" \
      && chmod 755 /usr/local/bin/flnet; \
    fi
COPY --chmod=755 common/dind.sh /opt/flnet-dind/dind.sh
COPY --chmod=755 platform/entrypoint.sh /opt/flnet-dind/entrypoint.sh
COPY platform/docker-compose.dind.yml /opt/flnet-dind/docker-compose.dind.yml

EXPOSE 8250 9150
ENTRYPOINT ["/opt/flnet-dind/entrypoint.sh"]
