{#if tool.federated}
from pathlib import Path

import numpy as np
import pandas as pd

from pyfedappwrap.learning.federated import BaseFederatedApp

from config import MyAppConfig, MyAppInputConfig, MyAppOutputConfig


class {tool.className}(BaseFederatedApp[MyAppConfig, MyAppInputConfig, MyAppOutputConfig]):
    """Federated client: every site computes a local result, the aggregator combines them."""

    def __init__(self):
        super().__init__()

    def run_train(self, data: MyAppInputConfig) -> MyAppOutputConfig:
        # TODO replace with your federated training logic
        df: pd.DataFrame = data.input
        numeric = df.select_dtypes(include="number")
        local_means = numeric.mean().to_numpy(dtype=float)

        # 1. send the local result to the aggregator (registered as "mean" in main.py)
        self.communicator.send_data_to_aggregator(
            local_means,
            aggregator_name="mean",
            communication_id=self.config.communication_id,
        )
        # 2. wait for the aggregated result of all participants
        response = self.communicator.await_data_from_aggregator(
            aggregator="mean",
            communication_id=self.config.communication_id,
            data_type=np.ndarray,
        )
        global_means = pd.DataFrame([response.data], columns=numeric.columns)
        self.logger.info("Received the aggregated result")
        return MyAppOutputConfig(output=global_means)

    def run_prediction(self, data: MyAppInputConfig) -> MyAppOutputConfig:
        return self.run_train(data)

    def _save(self) -> str:
        # TODO persist your model and return the file name
        path = Path("model.txt")
        path.write_text("model", encoding="utf-8")
        return str(path)

    def _load(self, path: str):
        # TODO load the model written by _save
        pass
{#else}
from pathlib import Path

import pandas as pd

from pyfedappwrap.learning.base_app import BaseApp

from config import MyAppConfig, MyAppInputConfig, MyAppOutputConfig


class {tool.className}(BaseApp[MyAppConfig, MyAppInputConfig, MyAppOutputConfig]):
    """Analysis: trains a model in run_train and applies it in run_prediction."""

    def __init__(self):
        super().__init__()
        self.means: pd.Series | None = None

    def run_train(self, data: MyAppInputConfig) -> MyAppOutputConfig:
        # TODO replace with your training logic; this example "learns" the column means
        df: pd.DataFrame = data.input
        numeric = df.select_dtypes(include="number")
        for epoch in range(self.config.epochs):
            self.send_metric("rows_seen", float(len(numeric) * (epoch + 1)), x=epoch)
        self.means = numeric.mean()
        self.logger.info("Training finished")
        return MyAppOutputConfig(output=self.means.to_frame().T)

    def run_prediction(self, data: MyAppInputConfig) -> MyAppOutputConfig:
        # TODO replace with your prediction logic
        df: pd.DataFrame = data.input
        numeric = df.select_dtypes(include="number")
        return MyAppOutputConfig(output=numeric - self.means)

    def _save(self) -> str:
        path = Path("model.csv")
        self.means.to_csv(path)
        return str(path)

    def _load(self, path: str):
        self.means = pd.read_csv(path, index_col=0).iloc[:, 0]
{/if}
