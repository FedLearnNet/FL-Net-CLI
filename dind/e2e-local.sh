#!/usr/bin/env bash
# Runs EndToEndTest with the local branches of the service repositories next to this one, e.g.
#   dind/e2e-local.sh learning-apis orchestration-api cli [-- -De2e.run-timeout-minutes=60]
# learning-apis and orchestration-api are built into images (from a copy, so the working trees and their target/
# stay untouched), cli builds flnet from this checkout. Needs docker and JAVA_HOME of a JDK 25.
set -euo pipefail

cd "$(dirname "$0")/.."
root="$(cd .. && pwd)"
tag=e2e-local
work="${TMPDIR:-/tmp}/flnet-e2e-local"
local_images=()
maven_args=()

# A copy of a repository without its build output and history
copy() {
  rm -rf "$work/$1"
  mkdir -p "$work/$1"
  rsync -a --exclude target --exclude .git --exclude node_modules "$root/$1/" "$work/$1/"
}

while [ $# -gt 0 ]; do
  case "$1" in
    learning-apis)
      echo "▷ Building Learning-APIs ($(git -C "$root/Learning-APIs" branch --show-current))"
      copy Learning-APIs
      (cd "$work/Learning-APIs" && ./mvnw -B -ntp package -DskipTests -Dquarkus.profile=staging)
      for module in global-learning-api local-learning-api datamodeler-api; do
        # no cache: copies keep their timestamps, so a cached layer can hold the build before
        docker build --no-cache -t "$module:$tag" -f "$work/Learning-APIs/$module/src/main/docker/Dockerfile.jvm" "$work/Learning-APIs/$module"
        local_images+=("$module=$module:$tag")
      done
      ;;
    orchestration-api)
      echo "▷ Building Orchestration-API ($(git -C "$root/Orchestration-API" branch --show-current))"
      copy Orchestration-API
      (cd "$work/Orchestration-API" && ./mvnw -B -ntp package -DskipTests)
      docker build --no-cache -t "orch-api:$tag" -f "$work/Orchestration-API/src/main/docker/Dockerfile.jvm" "$work/Orchestration-API"
      local_images+=("orch-api=orch-api:$tag")
      ;;
    cli)
      maven_args+=(-De2e.flnet-from-source=true)
      ;;
    --)
      shift
      maven_args+=("$@")
      break
      ;;
    *)
      echo "Unknown component '$1': learning-apis, orchestration-api or cli" >&2
      exit 2
      ;;
  esac
  shift
done

images="$(IFS=,; echo "${local_images[*]:-}")"
./mvnw test -Pe2e -De2e.local-images="$images" "${maven_args[@]}"
