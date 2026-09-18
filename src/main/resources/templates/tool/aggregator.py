from __future__ import annotations

from typing import Any, Optional

import numpy as np

from pyfedappwrap.engine.federated import AppAggregator, FLNetMessageMetaDTO


class MeanAggregator(AppAggregator):
    """Combines the results of all clients, here: the element-wise mean of their vectors."""

    def aggregate(
            self,
            data: list[Any],
            n_clients: int,
            meta: Optional[FLNetMessageMetaDTO] = None,
    ) -> Any:
        if not data:
            raise ValueError("Cannot aggregate an empty payload list.")

        # TODO implement your aggregation logic, e.g. weighted by sample counts
        return np.mean(np.stack(data), axis=0)
