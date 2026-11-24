# FL-Net Platform and Client in Docker (DinD)

A whole FL-Net Platform or Client in one container with its own Docker daemon. On start, the container runs
`flnet platform|client init` and `up` with the native flnet of a release. Only `docker-compose.dind.yml` of the
role is added on top. The inner daemon allocates networks like `FL-Net-Paper/dind/daemon.json`, off the
172.17.0.0/16 of the host's docker0.

```bash
docker build -f dind/platform/Dockerfile -t flnet-platform-dind dind   # --build-arg FLNET_VERSION=v1.0.0 for a tagged release
docker build -f dind/client/Dockerfile -t flnet-client-dind dind

docker run -d --privileged -p 8250:8250 flnet-platform-dind
docker run -d --privileged --add-host=host.docker.internal:host-gateway -p 4601:80 \
  -e DEPLOYED_ON_ADDRESS=http://localhost:4601 -e PLATFORM_USERNAME=clinic1 -e FLNET_PLATFORM_PASSWORD=... \
  flnet-client-dind
```

| Variable | Default | Meaning |
|---|---|---|
| `DEPLOYED_ON_ADDRESS` | `http://localhost` | Address the browser uses (the published port) |
| `CLINIC_NAME` | `default` | Instance name; each client in a network needs its own |
| `PLATFORM_USERNAME`, `FLNET_PLATFORM_PASSWORD` | | Platform account of the client, for federation |
| `FLNET_CLIENT_ARGS` | `--network custom --platform-url http://host.docker.internal:8250 --platform-relay-port 9150` | Flags for `flnet client init` |
| `FLNET_IMAGE_TAG` | `latest` (build arg `IMAGE_TAG`) | Tag of the FL-Net service images |

Compose files mounted into `/compose-extra/` are added on start. Remove containers with
`docker rm -f -v`, so their inner Docker storage goes with them.

Example: [`examples/us-130`](examples/us-130), several US-130 clinics like
`meta/deployment/start_us-130_clincs.sh`:

```bash
DATASET_DIR=<meta>/deployment/example-data/us-130/clinics PLATFORM_USERNAME=... FLNET_PLATFORM_PASSWORD=... \
  dind/examples/us-130/start.sh 3 4601
dind/examples/us-130/start.sh stop
```

## A platform and clients on one machine

[`deploy.sh`](deploy.sh) starts a platform and federated clients as DinD containers on one docker network, with
platform accounts for the clients and an `admin` login for each frontend; data stays in docker volumes:

```bash
dind/deploy.sh up --clients 2                     # platform http://localhost:8250, clients http://localhost:4601, 4602
dind/deploy.sh status
dind/deploy.sh down                               # --volumes also removes the data
```

`--local-images global-learning-api=global-learning-api:mine,...` uses images built on this machine, e.g. from a
local branch, and `--flnet` a linux musl flnet instead of a release's.

## End-to-end test

`EndToEndTest` (`./mvnw test -Pe2e`) runs a platform and two clients from these images on one docker network and
checks the important features, see its Javadoc. [`e2e-local.sh`](e2e-local.sh) runs it with the local branches of
the repositories next to this one, e.g. `dind/e2e-local.sh learning-apis orchestration-api cli`.
[`.github/workflows/e2e.yml`](../.github/workflows/e2e.yml) runs it nightly, on demand and for other repositories
(`workflow_call`), which pass their fresh images.

[`.github/workflows/dind.yml`](../.github/workflows/dind.yml) runs after each release of flnet, tests both images
and publishes `ghcr.io/fedlearnnet/flnet-platform-dind` and `ghcr.io/fedlearnnet/flnet-client-dind`
(`main` → `:latest`, `vX` → `:vX`).
