#!/bin/sh
set -eu

. /opt/flnet-dind/dind.sh

PLATFORM_NAME="${PLATFORM_NAME:-default}"
PLATFORM_PORT="${PLATFORM_PORT:-8250}"
RELAY_PORT="${RELAY_PORT:-9150}"
FLNET_PLATFORM_ARGS="${FLNET_PLATFORM_ARGS:-}"
PLATFORM_ALIASES="${PLATFORM_ALIASES:-}"
CERTS=/var/lib/flnet-dind/certs

# The platform's address and those of its aliases on the port; the global API accepts their origins (CORS), which
# clients send when they open their websocket
PLATFORM_ORIGINS="http://localhost:$PLATFORM_PORT"
san="DNS:localhost"
for alias in $(echo "$PLATFORM_ALIASES" | tr ',' ' '); do
  PLATFORM_ORIGINS="$PLATFORM_ORIGINS,http://$alias:$PLATFORM_PORT"
  san="$san,DNS:$alias"
done
export PLATFORM_ORIGINS

start_dockerd
load_images

# The relay needs a certificate; without one mounted a self-signed one, which it accepts in dev mode (docker-compose.dind.yml)
SSL_CERT="${SSL_CERT:-$CERTS/fullchain.pem}"
SSL_KEY="${SSL_KEY:-$CERTS/privkey.pem}"
if [ ! -f "$SSL_CERT" ]; then
  mkdir -p "$CERTS"
  openssl req -x509 -newkey rsa:2048 -nodes -days 365 -subj /CN=localhost -addext "subjectAltName=$san" \
    -keyout "$SSL_KEY" -out "$SSL_CERT" 2>/dev/null
fi

echo "▷ Setting up platform '$PLATFORM_NAME' on port $PLATFORM_PORT"

# The domain is localhost, so the CLI binds nginx to 127.0.0.1 without SSL; it keeps the same port inside and
# outside the container, so the address is the same for the host and for the platform's own tool registry
flnet platform init --no-input --yes --name "$PLATFORM_NAME" --image-tag "$FLNET_IMAGE_TAG" \
  --domain "http://localhost:$PLATFORM_PORT" --port "$PLATFORM_PORT" --relay-port "$RELAY_PORT" \
  --ssl-cert "$SSL_CERT" --ssl-key "$SSL_KEY" --no-nginx-ssl $FLNET_PLATFORM_ARGS

write_extra_hosts /opt/flnet-dind/docker-compose.hosts.yml global-learning-api orch-api
COMPOSE_FILE="$(compose_files /opt/flnet-dind/docker-compose.dind.yml /opt/flnet-dind/docker-compose.hosts.yml)"
export COMPOSE_FILE

# Published ports and other containers reach this container's addresses, the nginx listens on 127.0.0.1 only
for ip in $(hostname -i); do
  socat "TCP-LISTEN:$PLATFORM_PORT,bind=$ip,fork,reuseaddr" "TCP:127.0.0.1:$PLATFORM_PORT" &
done

stop() {
  flnet platform stop --no-input || true
  stop_dockerd
  exit 0
}
trap stop INT TERM

if ! flnet_up platform; then
  flnet platform status --no-input || true
  flnet platform logs --no-input --tail 200 || true
  exit 1
fi

flnet platform logs --no-input --follow --tail 50 &
wait $!
