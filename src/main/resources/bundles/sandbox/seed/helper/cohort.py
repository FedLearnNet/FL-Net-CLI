"""
Contains helper functions for cohorts of the local learning API.
"""
from .auth import get_auth_headers
from .util import request_with_renew_auth


def get_or_create_cohort(local_learning_api_url: str, name: str, desc: str,
                         global_schema_id: str, request_auth_headers: dict) -> int:
    """
    Returns the id of the cohort with the given name. Creates the cohort if it
    does not exist yet.

    Args:
        local_learning_api_url: Base URL of the local learning API
        name: The name of the cohort
        desc: The description used if the cohort is created
        global_schema_id: The id of the root schemanode in the global datamodeler
        request_auth_headers: The headers to use for auth header renewal
    Returns:
        The id of the cohort
    Raises:
        ValueError if a cohort with that name exists on a different schema or
        if the cohort could not be created.
    """
    auth_headers = get_auth_headers(**request_auth_headers)
    response, auth_headers = request_with_renew_auth(
        method="GET",
        url=f"{local_learning_api_url}/cohort",
        body=None,
        auth_headers=auth_headers,
        request_auth_headers=request_auth_headers
    )
    if response.status_code != 200:
        raise ValueError(f"Failed to list cohorts: {response.text} with status code: {response.status_code}")

    for cohort in response.json():
        if cohort.get("name") == name:
            if cohort.get("globalSchemaId") != global_schema_id:
                raise ValueError(f"ERROR: cohort with name {name} already exists but has a different global schema ID.")
            print(f"WARNING: cohort with name {name} already exists but has the correct schema")
            return cohort.get("id")

    body_dict = {
        "name": name,
        "description": desc,
        "globalSchemaID": global_schema_id,
    }
    response, auth_headers = request_with_renew_auth(
        method="POST",
        url=f"{local_learning_api_url}/cohort",
        body=body_dict,
        auth_headers=auth_headers,
        request_auth_headers=request_auth_headers
    )
    if response.status_code != 200:
        raise ValueError(f"Failed to create cohort: {response.text} with status code: {response.status_code}")

    return response.json().get("id")
