import pandas as pd

from pyfedappwrap.types.databaseadopter.base_importer import BaseExtractorAdopterAPP

from config import MyAppConfig, MyAppInputConfig, MyAppOutputConfig


class {tool.className}(BaseExtractorAdopterAPP[MyAppConfig, MyAppInputConfig, MyAppOutputConfig]):
    """Extractor: has no input, reads data from a source (e.g. a database) and returns it."""

    def __init__(self):
        super().__init__()

    def run_adopter(self) -> MyAppOutputConfig:
        # TODO replace with your extraction logic, e.g. a database query
        ids = list(range(1, self.config.rows + 1))
        df = pd.DataFrame({|{"id": ids, "value": ["row-" + str(i) for i in ids]}|})
        return MyAppOutputConfig(output=self.to_csv(df))
