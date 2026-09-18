from pathlib import Path

import pandas as pd

from pyfedappwrap.learning.app_types import BaseEvaluationApp

from config import MyAppConfig, MyAppInputConfig, MyAppOutputConfig


class {tool.className}(BaseEvaluationApp[MyAppConfig, MyAppInputConfig, MyAppOutputConfig]):

    def __init__(self):
        super().__init__()

    def run_evaluate(self, data: MyAppInputConfig) -> MyAppOutputConfig:
        # TODO replace with your evaluation logic
        df: pd.DataFrame = data.input
        missing = int(df.isna().sum().sum())
        self.send_metric("rows", float(len(df)))
        self.send_metric("columns", float(len(df.columns)))
        self.send_metric("missing_cells", float(missing))

        report = Path(self.config.report_name)
        report.write_text(
            "rows=" + str(len(df)) + "\ncolumns=" + str(len(df.columns)) + "\nmissing_cells=" + str(missing) + "\n",
            encoding="utf-8",
        )
        return MyAppOutputConfig(report=report)
