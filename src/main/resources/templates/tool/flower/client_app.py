"""{tool.className}: a Flower ClientApp - trains/evaluates on this site's own local data."""
import numpy as np
from flwr.app import ArrayRecord, Context, Message, MetricRecord, RecordDict
from flwr.clientapp import ClientApp

app = ClientApp()


@app.train()
def train(msg: Message, context: Context) -> Message:
    # TODO replace with your real local training logic. This site's own input data lives under
    # pyfedappwrap.engine.config.system_config.system_settings.data_dir (same convention every
    # other FL-Net tool uses) - never send the raw data itself back, only derived results.
    ndarrays = msg.content["arrays"].to_numpy_ndarrays()
    model = [layer + np.random.rand(*layer.shape) for layer in ndarrays]

    content = RecordDict(\{
        "arrays": ArrayRecord(model),
        "metrics": MetricRecord(\{"num-examples": 1\}),
    \})
    return Message(content=content, reply_to=msg)


@app.evaluate()
def evaluate(msg: Message, context: Context) -> Message:
    # TODO replace with your real local evaluation logic
    content = RecordDict(\{"metrics": MetricRecord(\{"num-examples": 1\})\})
    return Message(content=content, reply_to=msg)
