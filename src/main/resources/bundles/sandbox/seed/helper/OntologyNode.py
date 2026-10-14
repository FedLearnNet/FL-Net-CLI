from typing import List, Union, Optional, TYPE_CHECKING

import requests

if TYPE_CHECKING:
    from .Schema import Schema

class OntologyNode:
    """
    Contains one specific ontology node, e.g. place of birth and all relevant information
    of that ontology node.
    Contains connected ontologies in the form of a tree structure.
    """
    ontology_id: str
        # the id of the ontology in the ontology system
        # Only used in this code and not at the data modeler server
        # The datamodeler server uses the db_datatype_id for that
    ontology_name: str
        # the name of the ontology
    ontology_desc: str
        # the description of the ontology, Optional
    parent_ontology: Union["OntologyNode", None]
        # the parent ontology, if None we are at root level
        # TODO: should be multiple at some point
    children_ontologies: List["OntologyNode"]
        # the children ontologies, if empty we are at a leaf node
        # might be initalized empty and filled later
    db_ontology_id: Union[str, None]
    schema: Optional["Schema"]
    depth: int
        # the depth of the ontology in the ontology tree, root ontology has depth 0
    def __init__(self,
                 ontology_id: str,
                 ontology_name: str,
                 ontology_desc: str,
                 parent_ontology: Union["OntologyNode", None] = None,
                 children_ontologies: Union[List["OntologyNode"], None] = None,
                 db_ontology_id: Union[str, None] = None,
                 schema: Optional["Schema"] = None) -> None:
        if children_ontologies is None:
            children_ontologies = []
        self.ontology_id = ontology_id
        self.ontology_name = ontology_name
        self.ontology_desc = ontology_desc
        self.parent_ontology = parent_ontology
        self.children_ontologies = children_ontologies
        self.db_ontology_id = db_ontology_id
        self.schema = schema

        # Calculate depth
        if parent_ontology is None:
            self.depth = 0  # Root ontology
        else:
            self.depth = parent_ontology.depth + 1

    def calculate_depth(self) -> int:
        """
        Calculates and returns the depth of this ontology
        Root ontologies have depth 0
        """
        if self.parent_ontology is None:
            return 0
        return self.parent_ontology.calculate_depth() + 1

    def update_depth(self) -> None:
        """
        Updates the depth attribute by calculating it from the parent ontologies
        """
        self.depth = self.calculate_depth()

    def set_schema(self, schema: "Schema") -> None:
        """
        Set the parent schema reference for this node.
        """
        self.schema = schema

    def upload(self) -> None:
        """
        Upload this ontology node to the configured datamodeler.
        """
        if self.db_ontology_id:
            return
        if self.schema is None:
            raise ValueError("Ontology has no associated schema. Cannot upload.")
        if self.schema.global_server_url is None:
            raise ValueError("Global server URL is not set, use set_global_server_url() before uploading anything.")

        url = self.schema.global_server_url + "/ontology/"
        body_dict = {
            "ontology": {
                "names": [self.ontology_name],
                "description": self.ontology_desc,
                "cui": self.ontology_id
            }
        }

        # backend infers target; sourceID references parent relation
        if self.parent_ontology is not None:
            body_dict["edge"] = {
                "sourceID": self.parent_ontology.db_ontology_id,
            }

        response = requests.post(url, json=body_dict, timeout=60)
        if response.status_code in [201, 409]:
            response_dict = response.json()
            try:
                self.db_ontology_id = response_dict["nodes"][0]["id"]
            except (KeyError, IndexError):
                raise ValueError(
                    f"Conflict uploading ontology {self.ontology_name} but no id in response: {response.text}"
                )
            return

        raise ValueError(f"Failed to upload ontology {self.ontology_name}: {response.text}")
