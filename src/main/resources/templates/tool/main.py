from pyfedappwrap.engine.config.system_config import system_settings
from pyfedappwrap.engine.runtime import FedDBEngine

{#if tool.flower}
from pyfedappwrap.learning.federated.flower_app import FlowerApp

print(system_settings)

engine = FedDBEngine()

# The Flower App bundle (ClientApp/ServerApp, its own pyproject.toml) lives in flower_app/ -
# fill in flower_app/{tool.pythonPackage}/server_app.py and client_app.py. The engine starts
# and supervises Flower's own SuperLink/SuperNode process for you; you never call `flwr` yourself.
# model_file is the file server_app.py saves the final model to, inside model_dir() (task.py):
# the platform's run only finishes once that file was written during the run, and it is then
# uploaded as the trained model. Keep the two names in sync if you rename it.
engine.register_flower(FlowerApp(app_dir="flower_app", model_file="model.npz"))
{#else}
{#if tool.federated}
from aggregator import MeanAggregator
{/if}
from app import {tool.className}

print(system_settings)

engine = FedDBEngine()

{#if tool.federated}
# The key must match the aggregator_name the client app sends to (see app.py).
engine.register_aggregator(MeanAggregator(), "mean")
engine.register_federated({tool.className}())
{#else if tool.type == "DATA_TRANSFORMATION"}
engine.register_transformer({tool.className}())
{#else}
engine.register({tool.className}())
{/if}
{/if}

if __name__ == "__main__":
    engine.start()
    engine.wait_until_stop()
