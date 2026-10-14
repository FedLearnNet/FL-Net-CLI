"""
Contains helper functions for connectors of the local learning API:
creating a connector from an exported json, attaching an input file and running it.
"""
import json
import os
import time
import uuid
from typing import Optional

from .auth import get_auth_headers
from .util import request_with_renew_auth

FINISHED_IMPORT_PHASES = ["SUCCEEDED", "REFUSED", "FAILED"]
FINISHED_RUN_STATUS = ["FINISHED", "ERROR"]


def load_connector_json(connector_json_path: str) -> dict:
    """
    Reads an exported connector json file.
    """
    with open(connector_json_path, "r", encoding="utf-8") as f:
        return json.load(f)


def get_or_create_connector(local_learning_api_url: str, cohort_id: int, connector_config: dict,
                            name: str, request_auth_headers: dict) -> int:
    """
    Returns the id of the connector with the given name in the given cohort.
    Creates the connector from the given exported connector config if it does
    not exist yet.

    The connector is created the same way the UI imports a connector json
    (raw=true). The local learning API then rematches the schema ids of the
    mapping to the schema of the cohort and appends a random suffix to the name.

    Args:
        local_learning_api_url: Base URL of the local learning API
        cohort_id: The cohort the connector belongs to
        connector_config: The content of an exported connector json
        name: The name of the connector
        request_auth_headers: The headers to use for auth header renewal
    Returns:
        The id of the connector
    """
    auth_headers = get_auth_headers(**request_auth_headers)
    response, auth_headers = request_with_renew_auth(
        method="GET",
        url=f"{local_learning_api_url}/connectors",
        body=None,
        auth_headers=auth_headers,
        request_auth_headers=request_auth_headers,
        params={"cohort_id": cohort_id}
    )
    if response.status_code != 200:
        raise ValueError(f"Failed to list connectors: {response.text} with status code: {response.status_code}")

    for connector in response.json():
        connector_name = connector.get("name") or ""
        # raw imports get a random suffix appended to the name
        if connector_name == name or connector_name.startswith(f"{name}_"):
            print(f"WARNING: connector with name {connector_name} already exists, using it")
            return connector.get("id")

    body_dict = dict(connector_config)
    body_dict.pop("lastRun", None)
    body_dict["name"] = name
    body_dict["cohortId"] = cohort_id
    response, auth_headers = request_with_renew_auth(
        method="POST",
        url=f"{local_learning_api_url}/connectors",
        body=body_dict,
        auth_headers=auth_headers,
        request_auth_headers=request_auth_headers,
        params={"raw": "true"}
    )
    if response.status_code not in [200, 201]:
        raise ValueError(f"Failed to create connector: {response.text} with status code: {response.status_code}")

    return response.json().get("id")


def _upload_settings(data_filename: str, input_config: Optional[dict]) -> dict:
    """
    Builds the parsing settings for the given file. The file type is derived
    from the file extension, the csv settings are taken from the input config
    of the connector.
    """
    input_config = input_config or {}
    lower_name = data_filename.lower()
    settings = {
        "fileType": "CSV",
        "delimiter": input_config.get("delimiter") or ",",
        "customDelimiter": input_config.get("customDelimiter"),
        "hasHeader": input_config.get("hasHeader", True),
        "firstSheetOnly": True,
    }
    if lower_name.endswith(".zip"):
        settings["fileType"] = "MULTIPLE_CSV_ZIP"
        settings["firstSheetOnly"] = False
    elif lower_name.endswith((".xlsx", ".xls")):
        settings["fileType"] = "EXCEL"
    elif lower_name.endswith(".json"):
        settings["fileType"] = "JSON"
    return settings


def upload_connector_input(local_learning_api_url: str, cohort_id: int, connector_id: int,
                           data_filename: str, input_config: Optional[dict],
                           request_auth_headers: dict,
                           poll_interval: float = 2, timeout: float = 1800) -> int:
    """
    Uploads the given file (csv, zip, ...) as the input file of the connector
    and waits until the local learning API imported it.

    Args:
        local_learning_api_url: Base URL of the local learning API
        cohort_id: The cohort the connector belongs to
        connector_id: The connector that reads the file
        data_filename: The file to upload
        input_config: The inputConfig of the connector json, used for the csv settings
        request_auth_headers: The headers to use for auth header renewal
        poll_interval: Seconds between two checks of the import
        timeout: Seconds after which waiting for the import is given up
    Returns:
        The id of the stored file
    """
    import_id = str(uuid.uuid4())
    settings = _upload_settings(data_filename, input_config)

    def files_factory() -> dict:
        with open(data_filename, "rb") as f:
            content = f.read()
        return {
            "file": (os.path.basename(data_filename), content, "application/octet-stream"),
            "settings": (None, json.dumps(settings), "application/json"),
        }

    auth_headers = get_auth_headers(**request_auth_headers)
    # The answer is a stream of import events that ends with the import, the
    # state of the import is requested below.
    response, auth_headers = request_with_renew_auth(
        method="POST",
        url=f"{local_learning_api_url}/connectors/files/cohorts/{cohort_id}/files",
        body=None,
        auth_headers=auth_headers,
        request_auth_headers=request_auth_headers,
        params={"connectorId": connector_id, "importId": import_id},
        files_factory=files_factory
    )
    if response.status_code != 200:
        raise ValueError(f"Failed to upload {data_filename}: {response.text} with status code: {response.status_code}")

    deadline = time.monotonic() + timeout
    while True:
        response, auth_headers = request_with_renew_auth(
            method="GET",
            url=f"{local_learning_api_url}/connectors/files/imports/{import_id}",
            body=None,
            auth_headers=auth_headers,
            request_auth_headers=request_auth_headers
        )
        if response.status_code != 200:
            raise ValueError(f"Failed to get import of {data_filename}: {response.text} with status code: {response.status_code}")

        import_progress = response.json()
        phase = import_progress.get("phase")
        if phase in FINISHED_IMPORT_PHASES:
            break
        if time.monotonic() > deadline:
            raise ValueError(f"Import of {data_filename} did not finish within {timeout} seconds, last phase: {phase}")
        print(f"Import of {data_filename} is in phase {phase}...")
        time.sleep(poll_interval)

    if phase == "REFUSED":
        raise ValueError(f"Connector {connector_id} refused {data_filename}: {import_progress.get('refusal')}")
    if phase != "SUCCEEDED":
        raise ValueError(f"Import of {data_filename} failed: {import_progress.get('errorMessage')}")

    return import_progress.get("fileId")


def run_connector(local_learning_api_url: str, connector_id: int, request_auth_headers: dict,
                  delete_existing_patients: bool = False,
                  poll_interval: float = 2, timeout: float = 1800) -> dict:
    """
    Runs the connector and waits until the run finished.

    Args:
        local_learning_api_url: Base URL of the local learning API
        connector_id: The connector to run
        request_auth_headers: The headers to use for auth header renewal
        delete_existing_patients: If True all existing patients are deleted before the import
        poll_interval: Seconds between two checks of the run
        timeout: Seconds after which waiting for the run is given up
    Returns:
        The finished run as returned by the local learning API
    Raises:
        ValueError if the run could not be started, ended with an error or timed out.
    """
    auth_headers = get_auth_headers(**request_auth_headers)
    response, auth_headers = request_with_renew_auth(
        method="POST",
        url=f"{local_learning_api_url}/connectors/{connector_id}/run",
        body=None,
        auth_headers=auth_headers,
        request_auth_headers=request_auth_headers,
        params={"delete-existing-patients": str(delete_existing_patients).lower()}
    )
    if response.status_code not in [200, 201]:
        raise ValueError(f"Failed to run connector {connector_id}: {response.text} with status code: {response.status_code}")

    run = response.json()
    if isinstance(run, list):
        # the run is answered as a stream with one element
        if len(run) == 0:
            raise ValueError(f"Running connector {connector_id} did not return a run.")
        run = run[0]
    run_id = run.get("id")
    if run_id is None:
        raise ValueError(f"Running connector {connector_id} did not return a run id: {run}")

    deadline = time.monotonic() + timeout
    while run.get("status") not in FINISHED_RUN_STATUS:
        if time.monotonic() > deadline:
            raise ValueError(f"Run {run_id} of connector {connector_id} did not finish within {timeout} seconds.")
        print(f"Run {run_id} is {run.get('status')} in step {run.get('currentStep')}, progress: {run.get('progress')}")
        time.sleep(poll_interval)
        response, auth_headers = request_with_renew_auth(
            method="GET",
            url=f"{local_learning_api_url}/connectors/runs/{run_id}",
            body=None,
            auth_headers=auth_headers,
            request_auth_headers=request_auth_headers
        )
        if response.status_code != 200:
            raise ValueError(f"Failed to get run {run_id}: {response.text} with status code: {response.status_code}")
        run = response.json()

    if run.get("status") == "ERROR":
        raise ValueError(f"Run {run_id} of connector {connector_id} failed: {run.get('errorMessage')}")

    return run
