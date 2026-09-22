#!/bin/sh
# Builds the flnet CLI from source and runs it, forwarding all arguments.
#
#   ./build_and_run_locally.sh client init
#   ./build_and_run_locally.sh --help
#
# Set SKIP_BUILD=1 to just run the jar already built by a previous call.
set -eu

cd "$(dirname "$0")"

if [ "${SKIP_BUILD:-}" != "1" ]; then
    ./mvnw -q package -DskipTests
fi

exec java -jar target/quarkus-app/quarkus-run.jar "$@"
