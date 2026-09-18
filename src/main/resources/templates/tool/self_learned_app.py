from pathlib import Path

import pandas as pd

from pyfedappwrap.learning.app_types import BaseSelfLearnedApp

from config import MyAppConfig, MyAppInputConfig, MyAppOutputConfig


class {tool.className}(BaseSelfLearnedApp[MyAppConfig, MyAppInputConfig, MyAppOutputConfig]):

    def __init__(self):
        super().__init__()

    def run_algorithm(self, data: MyAppInputConfig) -> MyAppOutputConfig:
        # TODO replace with your algorithm
        df: pd.DataFrame = data.input
        for iteration in range(self.config.iterations):
            self.send_metric("progress", (iteration + 1) / self.config.iterations, x=iteration)

        report = Path("result.txt")
        report.write_text(df.describe(include="all").to_string(), encoding="utf-8")
        return MyAppOutputConfig(report=report)
