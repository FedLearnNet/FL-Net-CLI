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

It replaces `client_installer.py`, `create_self_signed_certs.py` (FL-Net-Client-Deployment),
`platform_installer.py` (FL-Net-Platform-Deployment) and the tool startup generator of the platform
(global-learning-api), with the same questions, files and secrets.

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

Running several instances needs deployment files with project-scoped container names
(`container_name: ${COMPOSE_PROJECT_NAME}-...`). Instances created with older files are refused with a hint to
run `init --refresh-files`.

## Configuration

All defaults live in `application.properties` under `flnet.*` (bound to `FLNetCliConfig`, as in the
Learning-APIs): networks, frontend images, ports, secret lengths, tool SDK version and base image, documentation
links. Help texts (`--help`) always show the effective values. Override them per machine in
`~/.config/flnet/application.properties`, or with environment variables (`flnet.client.port` →
`FLNET_CLIENT_PORT`) or system properties.

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

Then `flnet client init --network hospital` joins it. The instances home can be moved with `flnet.home`
(or `$FLNET_HOME`).

Platform and client web addresses use `http://host[:port]` or `https://host[:port]`, with an optional
trailing `/`. Hosts can be domain names (including punycode), `localhost`, or dotted-quad IPv4 addresses.
Paths, credentials, queries, fragments, IPv6, and IPv4 addresses with leading zeros are not supported.

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

The generated file contains only the services of the active profile (ssl/no-ssl) and uses absolute bind mount
paths into the deployment directory. By default, every value from `.env` and `env/*.env` is inlined, **including
all secrets**, so the file is written with owner-only permissions. `--keep-variables` keeps `${VAR}` references
and `env_file` entries instead, so the file contains no secrets. Needs the Docker CLI with the compose plugin,
but not a running daemon. Re-run it after every `init`.

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

## Development

Requirements: JDK 25 and Maven (wrapper included), e.g.
`export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`.
Native builds need GraalVM/Mandrel 25, or Docker for a container build.
The repository builds on its own: platform and client deployment files live in
`src/main/resources/bundles/platform` and `src/main/resources/bundles/client` and are embedded at
build time. No deployment repository checkout or download is needed. These files were initially
copied from `FL-Net-Platform-Deployment/FLNET_platform` and `FL-Net-Client-Deployment/FLNet_client`;
maintain the copies here when changing the CLI's default deployments. Maven generates the bundle
manifests automatically and excludes secrets, `.env` files, certificates and local data.
Custom build sources can still be selected with `-Dflnet.platform.bundle=...` / `-Dflnet.client.bundle=...`.

```bash
./mvnw quarkus:dev -Dquarkus.args='tool create demo --no-input'   # dev mode
./mvnw verify                                                      # tests
./mvnw package && java -jar target/quarkus-app/quarkus-run.jar --help
./mvnw package -Dnative                                            # native binary (GraalVM 25)
./mvnw package -Dnative -Dquarkus.native.container-build=true      # native binary via Docker
```

To try edited deployment files without rebuilding, pass the hidden
`--bundle-dir src/main/resources/bundles/client` option to `client init`
(or the platform directory to `platform init`). Existing deployment files are preserved unless
`--refresh-files` is also passed.

### Architecture

`bio.cosy.flnet.cli` contains only `FlnetCommand`, the picocli root command. Quarkus supplies the
application launcher; commands and supporting classes live in their feature packages. Each feature package
splits by role into `bo`, `command`, `config` and `questionnaire` subpackages (only those it needs); models,
enums and helpers stay in the feature package itself.

Three layers, as in the Learning-APIs, with shared technical helpers:

- **Model** (`base`): one object per set-up thing that holds all its settings and secrets. The base classes
  carry shared helpers and validation (`problems()` collects every problem, `requireValid()` fails with all of them):

  ```
  BaseFLNet                                  name, directory, validation helpers
  ├── BaseFLNetDeployableInstance            compose project, images, SSL files, Keycloak admin,
  │   │                                      ports, .env/secret-file (de)serialization
  │   ├── FLNetPlatformDeployment            domain, nginx/relay ports, min clients, platform secrets
  │   └── FLNetClientDeployment              network, platform login, permissions, web access, client secrets
  └── FLNetTool                              tool type, SDK/base image, .env switches (the `tool` of the templates)
  ```

  Loading an existing deployment fills the object from its `.env` and `env/*.env`, so reconfiguring starts
  from the current values, and saving writes exactly those files again.
- **Business objects** (`*BO`, `@ApplicationScoped`): `BaseFLNetDeploymentBO<T>` (listing, selection, create-or-reconfigure,
  port planning, saving), `FLNetClientDeploymentBO`, `FLNetPlatformDeploymentBO`, `FLNetDeploymentsBO` (both kinds),
  `ComposeBO` (docker compose), `FLNetToolBO` (rendering), and `FLNetNetworkBO` (configured network
  selection and frontend images).
- **Config** (`*Config`, Lombok, picocli mixins): the settings of `init` exactly as given on the command line;
  every field is `null` until given.
- **Questionnaires** (`*Questionnaire`, `@ApplicationScoped`): resolve every setting of a `*Config` step by step:
  a given value wins, otherwise the user is asked, and with `--no-input`/`--no-interactive` (or without a
  terminal) the default is used. Answers go straight into the model, so later defaults build on them.
- **Commands** (picocli): thin; select the instance, run the questionnaire, call the BO, print the result.
- **Helpers** (`*Helper`): static technical operations such as env-file I/O, secret generation,
  process execution and console output. Stateful collaborators such as `Prompter` and `PortPlanner`
  keep their descriptive names.

| Package    | Content                                                                              |
|------------|--------------------------------------------------------------------------------------|
| `base`     | the model classes above, `DeploymentKind`, `ToolType`, `FLNetToolField`, `.env` variable enums |
| `config`   | `CommandLineProducer`, `FLNetCliConfig` (all `flnet.*` settings)                     |
| `network`  | `FLNetNetwork`; `bo`: `FLNetNetworkBO` (configured networks, frontend images)          |
| `deploy`   | `PortPlanner`, embedded bundles, reports; `bo`: base/cross-kind BOs, `ComposeBO`; `command`: shared commands and `BaseInitCommand`; `config`/`questionnaire`: their base classes |
| `client`   | `ClientInitMode`; `bo`: deployment and certificate BOs; `command`: `client`, `init`, `certs`; `config`: `ClientConfig`, `CertificateConfig`; `questionnaire`: client and certificate questions, setup warnings |
| `platform` | `bo`, `command` (`platform`, `init`), `config` (`PlatformConfig`), `questionnaire`      |
| `tool`     | `bo`: `FLNetToolBO`; `command`: `tool create`; templates in `src/main/resources/templates/tool` (Qute)|
| `diagnostics` | `command`: `doctor` prerequisite checks                                           |
| `support`  | version reporting, prompting (flags, then prompt, then default), `.env` files, address validation |
| `migration` | JSON task POJOs, preview and version tracking; `bo`: `FLNetMigrationBO`; `command`: `migrate` |

### Deployment migrations

```bash
flnet migrate --kind client --name hospital-a --dry-run  # explain pending changes
flnet migrate --kind client --name hospital-a            # confirm and migrate to newest bundled revision
flnet migrate --kind platform --dir /srv/flnet --yes --no-input
```

The JSON catalog lists each revision's tasks and explains what will change. The command previews
those tasks, asks for confirmation, applies them and records the completed revision. Older deployments
without a recorded version need `--from <revision>`. There are no backups or automatic recovery;
containers are not restarted. The catalog currently starts at revision 1 with no production upgrade
tasks yet. See the [JSON migration guide](docs/migrations.md) and
`src/main/resources/migrations/catalog.json`.

## Releasing

Push a tag `vX.Y.Z`. [`.github/workflows/release.yml`](.github/workflows/release.yml) runs the tests,
builds native binaries for linux/macOS × amd64/arm64 (each smoke-tested) plus `flnet.jar`, and publishes
them with `install.sh` and `SHA256SUMS` as a GitHub release.
