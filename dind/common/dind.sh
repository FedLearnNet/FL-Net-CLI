#!/bin/sh
# Shared by the client and platform DinD entrypoints (sourced).
#
# DIND_INSECURE_REGISTRIES  registries the inner daemon pulls from over plain HTTP, e.g. a platform registry
# DIND_HOSTS                names the inner containers resolve to what this container resolves them to
# DIND_FORWARDS             port=host:port,... this container forwards from 127.0.0.1 (start_forwards)

unset DOCKER_HOST
# Docker sets it to the container id, and compose prefers it to the HOSTNAME of the instance's .env
unset HOSTNAME

start_dockerd() {
  echo "▷ Starting Docker daemon"
  set -- --bip 172.31.0.1/24 --default-address-pool base=172.30.0.0/16,size=24
  for registry in $(echo "${DIND_INSECURE_REGISTRIES:-}" | tr ',' ' '); do
    set -- "$@" --insecure-registry "$registry"
  done
  # Inner networks off 172.17.0.0/16: on Linux the host's docker0 uses 172.17.0.1, so host.docker.internal
  dockerd-entrypoint.sh dockerd "$@" >/var/log/dockerd.log 2>&1 &
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

  # Address of the machine running this container, for the inner containers
  HOST_DOCKER_INTERNAL_IP="$(getent ahostsv4 host.docker.internal | awk '{print $1; exit}' || true)"
  export HOST_DOCKER_INTERNAL_IP
}

# Loads the images mounted into /images/ (docker save archives), e.g. ones built from a local branch, so the
# deployment uses them instead of pulling
load_images() {
  for archive in /images/*.tar; do
    if [ -f "$archive" ]; then
      echo "▷ Loading $archive"
      docker load -i "$archive"
    fi
  done
}

# Forwards DIND_FORWARDS, port=host:port,..., from 127.0.0.1 of this container, e.g. a platform whose tool images
# name localhost:<port> for the inner daemon that pulls them
start_forwards() {
  for forward in $(echo "${DIND_FORWARDS:-}" | tr ',' ' '); do
    echo "▷ Forwarding 127.0.0.1:${forward%%=*} to ${forward#*=}"
    socat "TCP-LISTEN:${forward%%=*},bind=127.0.0.1,fork,reuseaddr" "TCP:${forward#*=}" &
  done
}

# Writes a compose file mapping DIND_HOSTS to their addresses for the given services; inner containers
# cannot reach the DNS of the network this container is in.
write_extra_hosts() {
  file="$1"
  shift
  hosts=""
  for host in $(echo "${DIND_HOSTS:-}" | tr ',' ' '); do
    ip="$(getent ahostsv4 "$host" | awk '{print $1; exit}' || true)"
    if [ -z "$ip" ]; then
      echo "Cannot resolve $host (DIND_HOSTS)" >&2
      exit 1
    fi
    hosts="$hosts      - \"$host:$ip\"
"
  done
  if [ -z "$hosts" ]; then
    echo "services: {}" >"$file"
    return
  fi
  echo "services:" >"$file"
  for service in "$@"; do
    printf '  %s:\n    extra_hosts:\n%s' "$service" "$hosts" >>"$file"
  done
}

# COMPOSE_FILE of the instance: its docker-compose.yml, the given files and every file mounted into /compose-extra/
compose_files() {
  files="docker-compose.yml"
  for file in "$@"; do
    files="$files:$file"
  done
  for extra in /compose-extra/*.yml; do
    if [ -f "$extra" ]; then
      echo "▷ Adding $extra" >&2
      files="$files:$extra"
    fi
  done
  echo "$files"
}

# flnet <kind> up, again after a failure: pulls in the inner daemon fail now and then, e.g. on a DNS hiccup that
# lasts minutes. The images are pulled first and again with longer pauses; pulled layers stay.
flnet_up() {
  for attempt in 1 2 3 4 5 6; do
    if flnet "$1" pull --no-input --no-restart; then
      break
    fi
    echo "▷ flnet $1 pull failed (attempt $attempt of 6)" >&2
    [ "$attempt" -eq 6 ] || sleep $((attempt * 20))
  done
  # up again until FLNET_UP_TIMEOUT: on a busy machine a service may take longer than its health check allows, it
  # keeps starting meanwhile
  deadline=$(($(date +%s) + ${FLNET_UP_TIMEOUT:-1200}))
  attempt=1
  until flnet "$1" up --no-input; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo "▷ flnet $1 up failed $attempt times in ${FLNET_UP_TIMEOUT:-1200} s" >&2
      return 1
    fi
    echo "▷ flnet $1 up failed (attempt $attempt), again in 30 s" >&2
    attempt=$((attempt + 1))
    sleep 30
  done
}

stop_dockerd() {
  kill "$dockerd_pid" 2>/dev/null || true
  wait "$dockerd_pid" 2>/dev/null || true
}
