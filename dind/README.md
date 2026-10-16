# FL-Net Client in Docker (DinD)

A whole FL-Net Client in one container with its own Docker daemon. On start, the container runs
`flnet client init` and `flnet client up` with the native flnet of a release. Only
[`docker-compose.dind.yml`](docker-compose.dind.yml) is added on top.

```bash
docker build -t flnet-client-dind dind      # --build-arg FLNET_VERSION=v1.0.0 for a tagged release
docker run -d --privileged --add-host=host.docker.internal:host-gateway -p 4601:80 \
  -e DEPLOYED_ON_ADDRESS=http://localhost:4601 flnet-client-dind
```

| Variable | Default | Meaning |
|---|---|---|
| `DEPLOYED_ON_ADDRESS` | `http://localhost` | Address the browser uses (the published port) |
| `CLINIC_NAME` | `default` | Instance name; each client in a network needs its own |
| `FLNET_CLIENT_ARGS` | `--network custom --platform-url http://host.docker.internal:8250 --platform-relay-port 9150 --no-platform-auth` | Flags for `flnet client init` |
| `FLNET_IMAGE_TAG` | `latest` (build arg `IMAGE_TAG`) | Tag of the FL-Net service images |

Compose files mounted into `/compose-extra/` are added on start. Remove containers with
`docker rm -f -v`, so their inner Docker storage goes with them.

Example: [`examples/us-130`](examples/us-130), several US-130 clinics like
`meta/deployment/start_us-130_clincs.sh`:

```bash
DATASET_DIR=<meta>/deployment/example-data/us-130/clinics dind/examples/us-130/start.sh 3 4601
dind/examples/us-130/start.sh stop
```

[`.github/workflows/dind.yml`](../.github/workflows/dind.yml) runs after each release of flnet, tests the
image and publishes `ghcr.io/fedlearnnet/flnet-client-dind` (`main` → `:latest`, `vX` → `:vX`).
