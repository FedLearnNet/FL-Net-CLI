"""{tool.className}: a Flower ServerApp - aggregates the clients' local results each round."""

import numpy as np
from flwr.app import ArrayRecord, Context
from flwr.serverapp import Grid, ServerApp
from flwr.serverapp.strategy import FedAvg

from {tool.pythonPackage}.task import get_initial_model, model_dir

app = ServerApp()


@app.main()
def main(grid: Grid, context: Context) -> None:
    """Runs num-server-rounds rounds of FedAvg, then saves the aggregated global model."""
    num_rounds: int = context.run_config["num-server-rounds"]

    arrays = ArrayRecord(get_initial_model())
    strategy = FedAvg()

    result = strategy.start(grid=grid, initial_arrays=arrays, num_rounds=num_rounds)

    # TODO replace get_initial_model()/the strategy above with your own; model_dir() is where
    # FL-Net picks up the result (see task.py).
    ndarrays = result.arrays.to_numpy_ndarrays()
    np.savez(model_dir() / "model.npz", *ndarrays)
