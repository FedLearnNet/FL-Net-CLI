# FL-Net sandbox

A complete FL-Net network on this machine: one platform and {clients.size} clients, set up with `flnet sandbox init`.
Use it to try FL-Net or to test changes end to end. This file is rewritten by `flnet sandbox init`.

**{securityNotice}**

| Member | Address | Folder |
|---|---|---|
| platform | {platformUrl} (relay on port {relayPort}) | `platform/` |
{#for client in clients}
| {client.name} | {client.url} | `{client.folder}/` |
{/for}

Log in everywhere with user `{user}` and password `{password}`.

## Start, fill with data, stop

```bash
flnet sandbox up              # pull the images and start everything
flnet sandbox status
flnet sandbox seed            # upload the schema and import the test data (needs python3)
flnet sandbox logs platform -f
flnet sandbox down            # stop, keep the data
flnet sandbox clean           # remove containers and ALL data; --purge also removes this folder
```

The first start takes a few minutes: the platform builds the example tool and pushes it to its own tool
registry. If `seed` fails with a 502, the platform is not up yet; wait and run it again. If it reports an
error on the first run, run it a second time.

## Run a federated learning

1. Open the platform at {platformUrl} and run a query. There are only about 800 patients per client, so keep
   it generic, e.g. "age exists". If no variables are offered yet, reopen the query page.
2. Start a federated learning based on that query:
   - Dataset: take all variables (there is a button for it), pivot to wide format joining on `patient_id`
     and use e.g. "keep first" for duplicates.
   - Tool: "US130-Hospital Readmission Federated NN Tool". Click the gear icon of the tool to save the
     hyperparameters (the defaults work), and click the save button of the workflow settings.

If that runs through, the learning workflow works. If it fails, look at the logs
(`flnet sandbox logs <member>`).

## Without the flnet command

Every member folder is a regular docker compose project. `docker-compose.yml` is the production file,
`docker-compose.override.yml` holds what the sandbox changes, and docker compose merges the two on its own.

```bash
cd {directory}
(cd platform && docker compose up -d)
{#for client in clients}
(cd {client.folder} && docker compose up -d)
{/for}

cd seed && pip install -r requirements.txt
{#for command in seedCommands}
{command}
{/for}
```

Stop with `docker compose down` in each folder (`down -v` also deletes the data). The regular commands work
on a member too, e.g. `flnet client logs --dir {directory}/clients/c1`.

## Test your own images

Build the image of the service you changed (most repositories have `dev_scripts/build_local_docker_image.sh`)
and tell the sandbox to use it:

```bash
flnet sandbox images                              # the images in use
flnet sandbox images set orch-api my-orch-api:dev
flnet sandbox up                                  # recreates the changed containers
flnet sandbox images reset orch-api               # back to the published image
```

Services: {imageKeys}. One setting applies to the platform and all clients.
`flnet sandbox up` does not pull replaced images, so they only need to exist on this machine. A plain
`docker compose pull` fails for them; `docker compose up -d` works.
{#if images}

Replaced at the moment:

{#for image in images}
- `{image.key}`: `{image.image}`
{/for}
{/if}

## Test data

{datasetNotice}

Each client imports its own file:
{#for client in clients} `{client.name}`: `{client.data}`{#if client_hasNext},{/if}{/for}.
