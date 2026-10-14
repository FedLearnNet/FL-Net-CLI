"""
Contains utility functions for the datamodeler.
1. `clean_datamodeler`: Removes all schemanodes, ontologies, and datatypes from the datamodeler.
"""
from typing import Any, Callable, Optional, Tuple
import requests

from .SchemaNode import SchemaNode
from .auth import get_auth_headers

def clean_schema(datamodeler_url: str) -> None:
    """
    Removes ALL schemanodes from the datamodeler at the given URL via its REST API.
    """
    for schemanode in _yield_datamodeler_node(datamodeler_url, "schemas"):
        schemanode_id = schemanode["id"]
        requests.delete(f"{datamodeler_url}/schemas/{schemanode_id}", timeout=360)


def clean_datatypes(datamodeler_url: str) -> None:
    """
    Removes ALL datatypes from the datamodeler at the given URL via its REST API.
    """
    # get all datatypes
    for datatype in _yield_datamodeler_node(datamodeler_url, "datatype"):
        datatype_id = datatype["id"]
        requests.delete(f"{datamodeler_url}/datatype/{datatype_id}", timeout=360)


def clean_ontologies(datamodeler_url: str) -> None:
    """
    Removes ALL ontologies from the datamodeler at the given URL via its REST API.
    """
    # get all ontologies
    for ontology in _yield_datamodeler_node(datamodeler_url, "ontology"):
        ontology_id = ontology["id"]
        requests.delete(f"{datamodeler_url}/ontology/{ontology_id}", timeout=360)

def _yield_datamodeler_node(datamodeler_url: str, endpoint: str) -> Any:
    """
    Yields all nodes from the datamodeler at the given URL via its REST API.
    Handles pagination automatically.

    Args:
        datamodeler_url: Base URL of the datamodeler
        endpoint: API endpoint to query (e.g., "schemas", "datatype", "ontology")
    Yields:
        Each node as a dictionary
    """
    # Initial request for the first page and total count
    page = 0
    while True:
        print(f"Fetching page {page} of {endpoint}...")
        response = requests.get(f"{datamodeler_url}/{endpoint}?page={page}&page_size=50", timeout=360)
        if response.status_code != 200:
            raise ValueError(f"ERROR: Could not retrieve nodes from {datamodeler_url}/{endpoint}?page={page}&page_size=50. Status code: {response.status_code}")

        data = response.json()
        results = data.get("results", [])
        print(f"Retrieved {len(results)} nodes.")
        print(f"Total count of nodes: {data.get('totalCount', 'unknown')}")
        if len(results) == 0:
            break

        for node in results:
            yield node
        page += 1

def request_with_renew_auth(method: str, url: str, body: Optional[Any], auth_headers: dict, request_auth_headers: dict,
                            params: Optional[dict] = None,
                            files_factory: Optional[Callable[[], dict]] = None) -> Tuple[requests.Response, dict]:
    """
    Sends the request and renews authentication if needed (if 401 is answered.)
    Only retires once with new auth parameters.

    Args:
        method: One of POST, GET, PATCH, ...
        body: The body of the request
        auth_headers: The auth headers to use in the request
        request_auth_headers: The headers to use for auth header renewal
        params: Optional query parameters
        files_factory: Optional function returning the `files` argument of a multipart request.
            If given, the request is sent as multipart and body is ignored. It is called once
            per attempt, so the file handles are fresh on a retry.
    Returns:
        A tuple containing the response object and the updated auth headers.
    """
    def _send(headers: dict) -> requests.Response:
        if files_factory is not None:
            return requests.request(method, url, files=files_factory(), params=params, headers=headers, timeout=360)
        return requests.request(method, url, json=body, params=params, headers=headers, timeout=360)

    response = _send(auth_headers)
    if response.status_code == 401:
        # Renew auth headers
        new_auth_headers = get_auth_headers(**request_auth_headers)
        response = _send(new_auth_headers)
        return response, new_auth_headers
    return response, auth_headers

class DataEntry:
    value: Any
    corresponding_schemanode: SchemaNode
    timestamp: Optional[str]
    visit_id: Optional[str]

    def __init__(self, value: Any, corresponding_schemanode: SchemaNode, timestamp: Optional[str] = None, visit_id: Optional[str] = None):
        self.value = value
        self.corresponding_schemanode = corresponding_schemanode

        self.timestamp = timestamp
        self.visit_id = visit_id
