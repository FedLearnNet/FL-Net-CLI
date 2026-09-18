# {tool.name}

{tool.description}

A FL-Net tool of type `{tool.type}`{#if tool.federated} with federated learning support{/if}, built on the
[FL-Net Python Tool API](https://pypi.org/project/FL-Net-Python-Tool-API/) (`pyfedappwrap`).
Generated with `flnet tool create`.

## Quick start

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python main.py
```

`main.py` starts the engine and connects to the platform configured in `.env`
(`{tool.platformUrl}`). Set `APP_ID` in `.env` to the id of your tool on the platform first.
{#if tool.hasInputs}
Sample input data for local runs lives in `data/`.
{/if}

## Project structure

```
├─ app.py            # Your tool: {tool.className}
{#if tool.federated}
├─ aggregator.py     # Combines the results of all clients
{/if}
{#if tool.hasConfigModule}
├─ config.py         # Hyperparameter, input and output schemas (Pydantic)
{/if}
├─ main.py           # Registers the tool with the engine and starts it
├─ app.yml           # Tool metadata and configuration for the platform
├─ requirements.txt  # Python dependencies
├─ data/             # Sample input data for local testing
├─ .env              # Local settings (APP_ID, platform URLs), not committed
└─ Dockerfile        # Container image used by the platform
```

## Core concepts

The framework loads and validates hyperparameters into `self.config`, parses the input into
the input schema and turns your output into results:

- **Hyperparameters** (`MyAppConfig`): e.g. `self.config.{#if tool.hyperparams.isEmpty}<name>{#else}{tool.hyperparams.get(0).name}{/if}`.
- **Input** (`MyAppInputConfig`): tabular files (CSV, TSV) arrive as `pandas.DataFrame`, other files as paths.
- **Output** (`MyAppOutputConfig`): `Path` values are uploaded as files, `pandas.DataFrame` values are
  serialized and uploaded, primitive values stay in the JSON result.

Keep `config.py` and the `config` section of `app.yml` in sync: every field needs an entry in both.

## Logging and metrics

```python
self.logger.info("Training")
self.send_metric("loss", 0.42, x=epoch)   # name, value, optional x (e.g. step or epoch)
```
{#if tool.federated}

## Federated learning

Every participating site runs `run_train` on its own data and sends a local result to the aggregator
with `self.communicator.send_data_to_aggregator(...)`. The aggregator registered in `main.py` combines
all results in `MeanAggregator.aggregate` and returns the global result, which every client receives
with `self.communicator.await_data_from_aggregator(...)`. Only these intermediate results leave a site,
never the raw data.
{/if}
{#if tool.type == "DATA_TRANSFORMATION"}

## Data transformation

- **Single value transformation**: `transform(self, value: str) -> str`, applied to each value of a column.
- **Row transformation**: `transform(self, value: dict) -> dict`, applied to each row; use static keys.
{/if}
{#if tool.type == "EXTRACTOR"}

## Extractor

An extractor has no input. It reads data from a source such as a database and returns it in a structured format.
{/if}

## Build the image

```bash
docker build -t {tool.slug} .
```

Publish the tool on the platform to have it built, tested and scanned by the FL-Net tool build pipeline.
See the [tool developer documentation](https://federated-learning.net/documentation/docs/tool-dev/create-tool).
