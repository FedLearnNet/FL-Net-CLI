# flnet: the FL-Net command line

`flnet` sets up and operates [FL-Net](https://federated-learning.net/documentation/) deployments and
scaffolds new FL-Net tools, as a single binary without Python, git or a JDK on the target machine.

```
flnet client   init | certs | list | info | up | down | pull | stop | restart | clean | status | logs | compose    run a site that joins a network
flnet platform init | list | info | up | down | pull | stop | restart | clean | status | logs | compose           run your own network platform
flnet list                                                 all clients and platforms on this machine
flnet tool     create <name>                               start a new tool project
flnet doctor                                               check docker, docker compose, openssl
flnet generate-completion                                  bash/zsh completion script
```

## Install

Linux and macOS (x86_64, arm64):

```bash
curl -fsSL https://raw.githubusercontent.com/FedLearnNet/FL-Net-CLI/main/install.sh | sh
```

The script downloads the native binary for your platform from the GitHub release, verifies its
SHA-256 checksum and installs it to `~/.local/bin` (`/usr/local/bin` as root). Options:
`FLNET_VERSION=v1.0.0`, `FLNET_INSTALL_DIR=/opt/bin`, `FLNET_DOWNLOAD_URL=https://mirror.internal/flnet`
(internal mirror holding the release assets and `SHA256SUMS`).

Other platforms (e.g. Windows): download `flnet.jar` from the release page and run `java -jar flnet.jar` (Java 25+).

Shell completion: `source <(flnet generate-completion)` (add it to `~/.bashrc` / `~/.zshrc`).

## Set up a client

```bash
flnet doctor
flnet client init          # asks everything, explains every choice
flnet client certs         # only for --ssl self-signed
flnet client up
flnet client logs -f
```

The deployment lives in `~/fl-net/clients/default` (see [Several instances](#several-instances-on-one-machine)).
Re-running `flnet client init` reconfigures it: previous answers become the defaults, and the generated database and
Keycloak secrets are never changed. `--mode clean` regenerates them. That needs
`flnet client down --volumes`, which deletes all data, so both ask for confirmation.

## Set up a platform

```bash
flnet platform init --domain https://fl.example.org \
    --ssl-cert /etc/letsencrypt/live/fl.example.org/fullchain.pem \
    --ssl-key /etc/letsencrypt/live/fl.example.org/privkey.pem
flnet platform up
```

## Several instances on one machine

Every client and platform is a named instance in `$FLNET_HOME` (default `~/fl-net`):
`clients/<name>/` and `platforms/<name>/`. The first one is called `default`.

```bash
flnet client init                     # first client: 'default'
flnet client init                     # lists the existing clients, then asks for a name:
                                      #   existing name = reconfigure it, new name = add a client
flnet client init --name site-b       # the same, non-interactive
flnet list                            # all clients and platforms with address, ports and status
flnet client info                     # one client: its details; several: a table
flnet client up --name site-b         # with one client --name is optional; with several it asks (or fails in scripts)
```

- **Ports:** each instance gets free ports. The suggestion skips ports of all other clients and platforms and
  ports in use on the host, and an explicit `--port` that another instance uses is refused.
- **Isolation:** each instance is its own docker compose project (`fl-net-client`, `fl-net-client-site-b`, ...),
  so containers, volumes and networks never collide. An instance keeps its project name for its whole
  life, because its data volumes are bound to it.
- **Custom locations:** `--dir <path>` puts an instance somewhere else; it is linked into `$FLNET_HOME` so
  it still shows up in `list`.

## Configuration

Override defaults per machine in `~/.config/flnet/application.properties`, or with environment variables
(`flnet.client.port` → `FLNET_CLIENT_PORT`). `--help` on any command always shows the effective values.

For example, to offer your own network by name and make it the default:

```properties
flnet.networks.hospital.name=Hospital Net
flnet.networks.hospital.order=0
flnet.networks.hospital.platform-url=https://fl.hospital.example
flnet.networks.hospital.relay-port=9150
flnet.networks.hospital.client-auth=true
flnet.networks.hospital.frontend=fl-net
flnet.client.default-network=hospital
```

Then `flnet client init --network hospital` joins it. The instances home can be moved with `$FLNET_HOME`.

Platform and client web addresses use `http://host[:port]` or `https://host[:port]`, with an optional
trailing `/`. Hosts can be domain names (including punycode), `localhost`, or dotted-quad IPv4 addresses.

## Generate a docker compose file

`init` writes a regular docker compose project (`docker-compose.yml`, `.env`, `env/*.env`, nginx and
Keycloak files) that `flnet … up` or plain `docker compose up -d` runs. If you manage stacks elsewhere
(Portainer, Komodo, your own tooling) or want to review the effective setup, generate one standalone file:

```bash
flnet client compose                                   # <dir>/docker-compose.generated.yml
flnet platform compose -o stack.yml
flnet client init --compose                            # generate it right after init
flnet client compose -o - --keep-variables > share.yml # without secrets, to stdout
```

By default every value from `.env` and `env/*.env` is inlined, **including all secrets**, so the file
is written with owner-only permissions. `--keep-variables` keeps `${VAR}` references and `env_file`
entries instead, so the file contains no secrets. Needs the Docker CLI with the compose plugin, but
not a running daemon. Re-run it after every `init`.

## Scripted / unattended installs

Every question has a flag (`flnet client init --help`). With `--no-input`, or when no terminal is attached,
nothing is asked: defaults are used, and a missing required value fails with the name of its flag.
`--yes` confirms risky steps. Secrets are never passed as flags. The platform password comes from
`--platform-password-file` or `$FLNET_PLATFORM_PASSWORD`.

```bash
FLNET_PLATFORM_PASSWORD="$(cat /run/secrets/flnet)" flnet client init --no-input \
    --network custom --platform-url https://fl.example.org --platform-relay-port 9150 \
    --platform-username site-a --listen 0.0.0.0 --domain https://flnet.site-a.org \
    --ssl provided --ssl-cert /etc/ssl/site-a/fullchain.pem --ssl-key /etc/ssl/site-a/privkey.pem
```

Exit codes: `0` success, `1` failure, `2` invalid or missing input, `3` environment not ready
(docker missing, directory not initialized, certificate missing), `130` aborted.

## Create a tool

```bash
flnet tool create "Random Forest"                                  # asks for type and description
flnet tool create fed-mean --type analysis --federated --app-id 42
flnet tool create cleaner --type pre-processing --no-input
```

Types: `analysis` (optionally `--federated`: client app plus aggregator), `pre-processing`, `post-processing`,
`evaluation`, `self-learned`, `data-transformation`, `extractor`, `export`. The project contains `app.py`,
`config.py`, `main.py`, `app.yml`, `.env`, `requirements.txt`, `Dockerfile`, a README and sample data.
`config.py` and `app.yml` declare the same example fields, so every generated project runs as is:

```bash
cd random-forest && python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt
TEST_MODE=true python main.py      # offline run with generated test data
```

## Upgrading an existing deployment

```bash
flnet migrate --kind client --name hospital-a --dry-run  # explain pending changes
flnet migrate --kind client --name hospital-a            # confirm and migrate to newest bundled revision
```

Previews the pending changes, asks for confirmation, applies them and records the completed revision.
Older deployments without a recorded version need `--from <revision>`. There are no backups or automatic
recovery; containers are not restarted.

---

Building from source, architecture and the release process: see [CONTRIBUTING.md](CONTRIBUTING.md).
