from pyfedappwrap.engine.config.system_config import system_settings
from pyfedappwrap.engine.runtime import FedDBEngine

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

if __name__ == "__main__":
    engine.start()
    engine.wait_until_stop()
