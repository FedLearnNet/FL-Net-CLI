#!/bin/sh
set -eu

. /opt/flnet-dind/dind.sh

CLINIC_NAME="${CLINIC_NAME:-default}"
DEPLOYED_ON_ADDRESS="${DEPLOYED_ON_ADDRESS:-http://localhost}"
FLNET_CLIENT_ARGS="${FLNET_CLIENT_ARGS:---network custom --platform-url http://host.docker.internal:8250 --platform-relay-port 9150}"

start_dockerd
start_forwards
load_images

echo "▷ Setting up client '$CLINIC_NAME' for $DEPLOYED_ON_ADDRESS"

# the password comes from FLNET_PLATFORM_PASSWORD
if [ -n "${PLATFORM_USERNAME:-}" ]; then
  FLNET_CLIENT_ARGS="$FLNET_CLIENT_ARGS --platform-username $PLATFORM_USERNAME"
fi
flnet client init --no-input --yes --name "$CLINIC_NAME" --image-tag "$FLNET_IMAGE_TAG" \
  --listen 0.0.0.0 --port 80 --domain "$DEPLOYED_ON_ADDRESS" $FLNET_CLIENT_ARGS

write_extra_hosts /opt/flnet-dind/docker-compose.hosts.yml local-learning-api controller orch-api
COMPOSE_FILE="$(compose_files /opt/flnet-dind/docker-compose.dind.yml /opt/flnet-dind/docker-compose.hosts.yml)"
export COMPOSE_FILE

stop() {
  flnet client stop --no-input || true
  stop_dockerd
  exit 0
}
trap stop INT TERM

if ! flnet_up client; then
  flnet client status --no-input || true
  flnet client logs --no-input --tail 200 || true
  exit 1
fi

flnet client logs --no-input --follow --tail 50 &
wait $!
