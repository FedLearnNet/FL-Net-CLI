#!/usr/bin/env bash
# Starts US-130 clinics, each a FL-Net Client DinD container with its own clinic_NNN.csv, like
#   DATASET_DIR=<dir with clinic_001.csv ...> dind/examples/us-130/start.sh 3 4601
set -euo pipefail

cd "$(dirname "$0")"
IMAGE="${IMAGE:-flnet-client-dind}"
LABEL=bio.cosy.flnet.us-130

if [ "${1:-}" = stop ]; then
  ids="$(docker ps -aq --filter "label=$LABEL")"
  [ -z "$ids" ] || docker rm -f -v $ids >/dev/null
  exit 0
fi

count="${1:-3}"
first_port="${2:-4601}"
: "${DATASET_DIR:?set DATASET_DIR to the directory with clinic_001.csv ..., e.g. meta/deployment/example-data/us-130/clinics}"

for ((index = 1; index <= count; index++)); do
  id="$(printf '%03d' "$index")"
  port=$((first_port + index - 1))
  name="us-130-clinic-$id"
  data="$DATASET_DIR/clinic_$id.csv"
  [ -f "$data" ] || { echo "Missing dataset: $data" >&2; exit 1; }

  docker rm -f -v "$name" >/dev/null 2>&1 || true
  docker run -d --name "$name" --label "$LABEL" -p "$port:80" \
    --privileged --cgroupns host -v /sys/fs/cgroup:/sys/fs/cgroup:rw \
    --add-host=host.docker.internal:host-gateway \
    -v "$PWD/docker-compose.us-130.yml:/compose-extra/us-130.yml:ro" \
    -v "$PWD/us-130-clinics.json:/connectors/us-130-clinics.json:ro" \
    -v "$(realpath "$data"):/connectors/clinic.csv:ro" \
    -e CLINIC_NAME="clinic$index" \
    -e DEPLOYED_ON_ADDRESS="http://localhost:$port" \
    -e FLNET_IMAGE_TAG="${FLNET_IMAGE_TAG:-staging}" \
    -e FLNET_CLIENT_ARGS="--network custom --platform-url http://host.docker.internal:8080 --no-platform-auth" \
    "$IMAGE" >/dev/null
  echo "▷ $name: http://localhost:$port (docker logs -f $name)"
done
