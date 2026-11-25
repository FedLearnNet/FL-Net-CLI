"""Shared helpers for {tool.className}'s ServerApp and ClientApp."""

from pathlib import Path

import numpy as np

from pyfedappwrap.engine.config.system_config import system_settings


def get_initial_model() -> list[np.ndarray]:
    # TODO replace with your real model's initial weights
    return [np.zeros(3)]


def model_dir() -> Path:
    """Where the ServerApp should save the final model, so FL-Net picks it up correctly.

    The engine starts Flower with MODEL_DIR set to this directory (and as the working
    directory), so always save here, never to a path of your own."""
    path = Path(system_settings.model_dir)
    path.mkdir(parents=True, exist_ok=True)
    return path
