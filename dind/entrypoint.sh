#!/bin/sh
set -eu

unset DOCKER_HOST

CLINIC_NAME="${CLINIC_NAME:-default}"
DEPLOYED_ON_ADDRESS="${DEPLOYED_ON_ADDRESS:-http://localhost}"
FLNET_CLIENT_ARGS="${FLNET_CLIENT_ARGS:---network custom --platform-url http://host.docker.internal:8250 --platform-relay-port 9150 --no-platform-auth}"

echo "▷ Starting Docker daemon"
# Inner networks off 172.17.0.0/16: on Linux the host's docker0 uses 172.17.0.1, so host.docker.internal
dockerd-entrypoint.sh dockerd --bip 172.31.0.1/24 --default-address-pool base=172.30.0.0/16,size=24 \
  >/var/log/dockerd.log 2>&1 &
dockerd_pid=$!

timeout=60
until docker info >/dev/null 2>&1; do
  timeout=$((timeout - 1))
  if [ "$timeout" -le 0 ] || ! kill -0 "$dockerd_pid" 2>/dev/null; then
    echo "Docker daemon failed to start:" >&2
    tail -n 50 /var/log/dockerd.log >&2 || true
    exit 1
  fi
  sleep 1
done

# Address of the machine running this container, for the inner containers (see docker-compose.dind.yml)
HOST_DOCKER_INTERNAL_IP="$(getent ahostsv4 host.docker.internal | awk '{print $1; exit}' || true)"
export HOST_DOCKER_INTERNAL_IP

echo "▷ Setting up client '$CLINIC_NAME' for $DEPLOYED_ON_ADDRESS"

flnet client init --no-input --yes --name "$CLINIC_NAME" --image-tag "$FLNET_IMAGE_TAG" \
  --listen 0.0.0.0 --port 80 --domain "$DEPLOYED_ON_ADDRESS" $FLNET_CLIENT_ARGS

COMPOSE_FILE="docker-compose.yml:/opt/flnet-dind/docker-compose.dind.yml"
for extra in /compose-extra/*.yml; do
  if [ -f "$extra" ]; then
    echo "▷ Adding $extra"
    COMPOSE_FILE="$COMPOSE_FILE:$extra"
  fi
done
export COMPOSE_FILE

stop() {
  flnet client stop --no-input || true
  kill "$dockerd_pid" 2>/dev/null || true
  wait "$dockerd_pid" 2>/dev/null || true
  exit 0
}
trap stop INT TERM

if ! flnet client up --no-input; then
  flnet client status --no-input || true
  flnet client logs --no-input --tail 200 || true
  exit 1
fi

flnet client logs --no-input --follow --tail 50 &
wait $!
