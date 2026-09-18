from typing import Any

from pydantic.dataclasses import dataclass

from pyfedappwrap.learning.run_runfig import AppConfig, AppInputConfig, AppOutputConfig

# Keep these classes in sync with the "config" section of app.yml (or copy them from the
# config detail page of your tool on the platform).


@dataclass
class MyAppConfig(AppConfig):
    """Hyperparameters, available as self.config in the app."""
{#for field in tool.hyperparams}
    {field.name}: {field.pythonType} = {field.pythonDefault}
{#else}
    pass
{/for}


@dataclass
class MyAppInputConfig(AppInputConfig):
    """Inputs. Tabular files (CSV, TSV) arrive as pandas DataFrames."""
{#for field in tool.inputs}
    {field.name}: {field.pythonType} = {field.pythonDefault}
{#else}
    pass
{/for}


@dataclass
class MyAppOutputConfig(AppOutputConfig):
    """Outputs. Path values are uploaded as files, DataFrames are serialized and uploaded."""
{#for field in tool.outputs}
    {field.name}: {field.pythonType} = {field.pythonDefault}
{#else}
    pass
{/for}
