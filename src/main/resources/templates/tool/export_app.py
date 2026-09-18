from pathlib import Path

import pandas as pd

from pyfedappwrap.types.export.base_exporter import BaseExporterAPP

from config import MyAppConfig, MyAppOutputConfig


class {tool.className}(BaseExporterAPP[MyAppConfig, MyAppOutputConfig]):
    """Export: receives the selected data as a DataFrame and writes it to a target system."""

    def __init__(self):
        super().__init__()

    def export(self, df: pd.DataFrame) -> MyAppOutputConfig:
        # TODO replace with your export logic, e.g. upload to a data warehouse
        self.logger.info("Exporting " + str(len(df)) + " rows")
        self.send_metric("exported_rows", float(len(df)))

        report = Path(self.config.report_name)
        report.write_text("exported_rows=" + str(len(df)) + "\n", encoding="utf-8")
        return MyAppOutputConfig(report=report)
