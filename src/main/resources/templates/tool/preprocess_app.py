import pandas as pd

from pyfedappwrap.learning.app_types import BasePrePostProcessApp

from config import MyAppConfig, MyAppInputConfig, MyAppOutputConfig


class {tool.className}(BasePrePostProcessApp[MyAppConfig, MyAppInputConfig, MyAppOutputConfig]):

    def __init__(self):
        super().__init__()

    def run_process(self, data: MyAppInputConfig) -> MyAppOutputConfig:
        # TODO replace with your processing logic; this example drops incomplete and duplicated rows
        df: pd.DataFrame = data.input
        rows_before = len(df)
        df = df.dropna()
        if self.config.drop_duplicates:
            df = df.drop_duplicates()
        self.send_metric("removed_rows", float(rows_before - len(df)))
        return MyAppOutputConfig(output=df)
