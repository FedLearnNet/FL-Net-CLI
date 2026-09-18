from pydantic.dataclasses import dataclass

from pyfedappwrap.types.transformation.base_transformer import BaseTransformerAPP
from pyfedappwrap.types.transformation.transformer_config import BaseTransformerConfig


@dataclass
class TransformerConfig(BaseTransformerConfig):
    pass


class {tool.className}(BaseTransformerAPP[TransformerConfig]):
    """
    Single value transformation: called for every value of the selected column.
    For a row transformation, take and return a dict instead:

        def transform(self, value: dict) -> dict:
    """

    def __init__(self):
        super().__init__()

    def transform(self, value: str) -> str:
        # TODO replace with your transformation
        return value.strip().upper()
