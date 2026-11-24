#!/usr/bin/env bash
# Deploys a platform and local nodes (clients) as DinD containers on one docker network, the clients federated
# with the platform, e.g. to try FL-Net on one machine:
#   dind/deploy.sh up [--clients 2] [--port 8250] [--client-port 4601] [--image-tag staging] [--name flnet-dind]
#                     [--dind-image-tag latest] [--flnet <linux musl flnet>] [--local-images service=image,...]
#                     [--subnet 10.213.250.0/24]
#   dind/deploy.sh status|down [--name flnet-dind]     (down --volumes also removes the data)
# The platform is http://localhost:<port>, client n http://localhost:<client-port + n - 1>; accounts and passwords
# are printed and kept in ~/.flnet/dind/<name>/.
set -euo pipefail

cd "$(dirname "$0")"
command="${1:-}"
shift || true

name=flnet-dind
clients=2
port=8250
client_port=4601
image_tag=staging
dind_image_tag=""
flnet=""
local_images=""
volumes=false
# of its own, so the daemon's address pools need no room
subnet=10.213.250.0/24
relay_port=9150
platform_host=flnet-platform.test

while [ $# -gt 0 ]; do
  case "$1" in
    --name) name="$2"; shift ;;
    --clients) clients="$2"; shift ;;
    --port) port="$2"; shift ;;
    --client-port) client_port="$2"; shift ;;
    --image-tag) image_tag="$2"; shift ;;
    --dind-image-tag) dind_image_tag="$2"; shift ;;
    --flnet) flnet="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"; shift ;;
    --local-images) local_images="$2"; shift ;;
    --subnet) subnet="$2"; shift ;;
    --volumes) volumes=true ;;
    *) echo "Unknown option $1" >&2; exit 2 ;;
  esac
  shift
done

state="$HOME/.flnet/dind/$name"
label="flnet.dind=$name"

secret() {
  head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20
}

# a password kept in the state directory, so a second 'up' logs in with the same one
password() {
  file="$state/$1.password"
  [ -f "$file" ] || secret >"$file"
  cat "$file"
}

await() {
  echo "▷ Waiting for $1"
  for _ in $(seq 1 360); do
    if curl -fsS -o /dev/null "$1"; then
      return
    fi
    sleep 5
  done
  echo "$1 did not answer in 30 minutes, see docker logs $2" >&2
  exit 1
}

image() {
  if [ -n "$dind_image_tag" ]; then
    docker pull "ghcr.io/fedlearnnet/flnet-$1-dind:$dind_image_tag" >/dev/null
    echo "ghcr.io/fedlearnnet/flnet-$1-dind:$dind_image_tag"
    return
  fi
  version=latest
  [ -z "$flnet" ] || version=none
  docker build -q --build-arg FLNET_VERSION="$version" -f "$1/Dockerfile" -t "flnet-$1-dind:local" . >/dev/null
  echo "flnet-$1-dind:local"
}

# 'image:' overrides of one side's services from --local-images, and the archives its container loads
prepare_images() {
  side="$1"
  shift
  mkdir -p "$state/images-$side"
  echo "services:" >"$state/$side-images.yml"
  for entry in $(echo "$local_images" | tr ',' ' '); do
    service="${entry%%=*}"
    image="${entry#*=}"
    for known in "$@"; do
      if [ "$service" = "$known" ]; then
        printf '  %s:\n    image: %s\n    pull_policy: never\n' "$service" "$image" >>"$state/$side-images.yml"
        docker save -o "$state/images-$side/$service.tar" "$image"
      fi
    done
  done
  if [ "$(wc -l <"$state/$side-images.yml")" -eq 1 ]; then
    echo "services: {}" >"$state/$side-images.yml"
  fi
}

common_args() {
  args=(-d --privileged --restart unless-stopped --label "$label" --network "$name" -e "FLNET_IMAGE_TAG=$image_tag"
    -v "$state/$1-images.yml:/compose-extra/images.yml:ro" -v "$state/images-$1:/images:ro")
  [ -z "$flnet" ] || args+=(-v "$flnet:/usr/local/bin/flnet:ro")
}

up() {
  mkdir -p "$state"
  chmod 700 "$state"
  docker network inspect "$name" >/dev/null 2>&1 || docker network create --subnet "$subnet" --label "$label" "$name" >/dev/null
  platform_image="$(image platform)"
  client_image="$(image client)"
  prepare_images platform datamodeler-api global-learning-api orch-api controller relay-server frontend
  prepare_images client local-learning-api orch-api controller instance-manager-frontend

  if ! docker inspect "$name-platform" >/dev/null 2>&1; then
    echo "▷ Starting the platform on http://localhost:$port"
    common_args platform
    docker run "${args[@]}" --name "$name-platform" --network-alias "$platform_host" -p "$port:$port" \
      -v "$name-platform-docker:/var/lib/docker" -v "$name-platform-flnet:/var/lib/flnet" \
      -e PLATFORM_NAME=platform -e "PLATFORM_ALIASES=$platform_host" -e "PLATFORM_PORT=$port" -e "RELAY_PORT=$relay_port" \
      -e "FLNET_PLATFORM_ARGS=--min-clients $clients" "$platform_image" >/dev/null
  fi
  await "http://localhost:$port/auth/realms/FLNet-Platform" "$name-platform"
  docker exec -i "$name-platform" sh -s -- FLNet-Platform admin "$(password platform-admin)" Admin Data-Scientist Data-Modeler \
    <common/kc-user.sh >/dev/null

  cat >"$state/client.yml" <<EOF
# the clients reach the platform under its alias and port and trust the self-signed certificate of its relay
services:
  local-learning-api:
    environment:
      - FLNET_GLOBAL_SOCKET_URI=ws://$platform_host:$port/api
      # Keycloak's discovery names localhost, the platform's address for its own host
      - QUARKUS_OIDC_CLIENT_GLOBAL_WEBSOCKET_DISCOVERY_ENABLED=false
      - QUARKUS_OIDC_CLIENT_GLOBAL_WEBSOCKET_TOKEN_PATH=/protocol/openid-connect/token
  controller:
    environment:
      - RELAY_MODE=dev
      - RELAY_TLS_MODE=self-signed
  orch-api:
    environment:
      # the platform's tool images name its own address, forwarded to it (DIND_FORWARDS)
      - ORCH_DOCKER__PRIVATE__REGISTRY_URL=localhost:$port
EOF
  for n in $(seq 1 "$clients"); do
    container="$name-client-$n"
    account="client-$n"
    # the client logs in to the platform with this account when it starts
    docker exec -i "$name-platform" sh -s -- FLNet-Platform "$account" "$(password "$account")" <common/kc-user.sh >/dev/null
    if ! docker inspect "$container" >/dev/null 2>&1; then
      address="http://localhost:$((client_port + n - 1))"
      echo "▷ Starting client $n on $address"
      common_args client
      docker run "${args[@]}" --name "$container" -p "$((client_port + n - 1)):80" \
        -v "$container-docker:/var/lib/docker" -v "$container-flnet:/var/lib/flnet" \
        -v "$state/client.yml:/compose-extra/client.yml:ro" \
        -e "CLINIC_NAME=client$n" -e "DEPLOYED_ON_ADDRESS=$address" \
        -e "PLATFORM_USERNAME=$account" -e "FLNET_PLATFORM_PASSWORD=$(password "$account")" \
        -e "DIND_HOSTS=$platform_host" -e "DIND_INSECURE_REGISTRIES=$platform_host:$port" \
        -e "DIND_FORWARDS=$port=$platform_host:$port" \
        -e "FLNET_CLIENT_ARGS=--network custom --platform-url http://$platform_host:$port --platform-relay-port $relay_port --federation" \
        "$client_image" >/dev/null
    fi
  done
  for n in $(seq 1 "$clients"); do
    address="http://localhost:$((client_port + n - 1))"
    await "$address/auth/realms/FLNet-Client" "$name-client-$n"
    docker exec -i "$name-client-$n" sh -s -- FLNet-Client admin "$(password "client-$n-admin")" Admin Data-Admin Data-Access-Manager \
      <common/kc-user.sh >/dev/null
  done
  status
}

status() {
  echo "Platform  http://localhost:$port  admin / $(cat "$state/platform-admin.password" 2>/dev/null || echo '?')"
  for container in $(docker ps -a --filter "label=$label" --format '{{.Names}}' | grep -- '-client-' | sort); do
    n="${container##*-}"
    published="$(docker port "$container" 80/tcp 2>/dev/null | head -n 1 | sed 's/.*://')"
    echo "Client $n  http://localhost:${published:-?}  admin / $(cat "$state/client-$n-admin.password" 2>/dev/null || echo '?')"
  done
  docker ps -a --filter "label=$label" --format 'table {{.Names}}\t{{.Status}}'
  echo "Logs: docker logs -f <name>; the platform builds its tools after the start, which takes a while."
}

down() {
  containers="$(docker ps -aq --filter "label=$label")"
  [ -z "$containers" ] || docker rm -f -v $containers >/dev/null
  docker network rm "$name" >/dev/null 2>&1 || true
  if [ "$volumes" = true ]; then
    for volume in $(docker volume ls -q | grep -E "^$name-(platform|client-[0-9]+)-(docker|flnet)$" || true); do
      docker volume rm "$volume" >/dev/null
    done
    rm -rf "$state"
  fi
  echo "▷ Removed $name$([ "$volumes" = true ] && echo ' and its data')"
}

case "$command" in
  up) up ;;
  status) status ;;
  down) down ;;
  *) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
