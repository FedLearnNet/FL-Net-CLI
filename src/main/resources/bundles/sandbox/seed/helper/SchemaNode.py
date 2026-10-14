from typing import List, Union, Optional, TYPE_CHECKING
import uuid
import requests
from .OntologyNode import OntologyNode
from .DatatypeNode import DatatypeNode
from .SchemaNodeType import SchemaNodeType

if TYPE_CHECKING:
    from .Schema import Schema

class SchemaNode:
    """
    This class contains all relevant information for one specific schema node
    It also includes the parent and child nodes if they exist.
    It can be used to generate a schema cypher file and to generate data
    in e.g. csv format
    """
    schemanode_id: str
        # the id, it's auto generated in the init function if not given
        # Only used in this code and not at the data modeler server
        # The datamodeler server uses the db_datatype_id for that
    schemanode_name: str
        # the name of the schemanode
    schemanode_desc: str
        # the description of the schemanode, Optional
    schemanode_type: SchemaNodeType
        # whether this schemanode is a group node (has children) or an attribute node which can be atomic or list
        # explicitly set to avoid confusion when e.g. children are not yet added or
        # the datatype is not yet added
    schemanode_datatype: List[DatatypeNode]
        # the allowed datatypes of the schemanode
    schemanode_ontology: List[OntologyNode]
        # the linked ontologies of the schemanode
    is_timeseries: Optional[bool]
        # whether this schemanode represents a timeseries attribute
        # None for group nodes that haven't had children added yet
        # True/False for attribute nodes or group nodes with consistent children
    parent_node: Union["SchemaNode", None]
        # the parent schemanode, if None we are at the parent node
    children_nodes: List["SchemaNode"]
        # the children schemanodes, if empty we are at a leaf node
        # can be initalized empty and filled later
    db_schemanode_id: Union[str, None]
    ignore_upload: bool
        # if the schemanode should be ignored in the upload
        # will still generate data, but not upload it
    depth: int
        # the depth of the schemanode in the schema tree, root node has depth 0
    corresponding_column_name: str
    local_learning_schemanode_id: Optional[int] = None
    schema: Optional["Schema"]
    

    def __init__(self,
             schemanode_id:Union[str, None],
             schemanode_name:str,
             schemanode_desc:str,
             schemanode_type:SchemaNodeType,
             schemanode_datatype:List[DatatypeNode],
             schemanode_ontology:List[OntologyNode],
             parent_node:Union["SchemaNode", None],
             children_nodes:List["SchemaNode"],
             corresponding_column_name: str,
             db_schemanode_id: Union[str, None] = None,
             ignore_upload:bool = False,
             is_timeseries: Optional[bool] = None,
             schema: Optional["Schema"] = None) -> None:

        # schemanode_id
        if schemanode_id is None:
            # we generate a random UUID4
            schemanode_id = str(uuid.uuid4())
        self.schemanode_id = schemanode_id
        self.schemanode_name = schemanode_name
        self.schemanode_desc = schemanode_desc
        self.schemanode_type = schemanode_type
        self.schemanode_datatype = schemanode_datatype
        self.schemanode_ontology = schemanode_ontology
        self.parent_node = parent_node
        self.children_nodes = children_nodes
        self.db_schemanode_id = db_schemanode_id
        self.ignore_upload = ignore_upload
        self.corresponding_column_name = corresponding_column_name
        self.is_timeseries = is_timeseries
        self.schema = schema

        # Calculate depth
        if parent_node is None:
            self.depth = 0  # Root node
        else:
            self.depth = parent_node.depth + 1

    def get_dot_separated_path(self) -> str:
        """
        Returns the dot separated path of the schemanode
        """
        names = [self.schemanode_name]
            # we build this in reverse order, so idx 0 is this node
            # in the end we just reverse the list
        cur_parent = self.parent_node
        while cur_parent is not None:
            names.append(cur_parent.schemanode_name)
            cur_parent = cur_parent.parent_node if cur_parent else None
        return ".".join(reversed(names))

    def set_local_learning_schemanode_id(self, local_learning_schemanode_id: int) -> None:
        """
        Sets the local learning schema node ID.
        """
        self.local_learning_schemanode_id = local_learning_schemanode_id

    def get_local_learning_schemanode_id(self) -> Optional[int]:
        """
        Gets the local learning schema node ID.
        If not set, raises an ValueError
        """
        if self.local_learning_schemanode_id is None:
            raise ValueError("Local learning schema node ID is not set but trying to retrieve it.")
        return self.local_learning_schemanode_id

    def add_child(self, child: "SchemaNode") -> None:
        """
        Adds a child node and validates/updates is_timeseries consistency.

        For group nodes (nodes with children), is_timeseries is determined by their children:
        - If this is the first child, set the group's is_timeseries to match the child
        - If adding subsequent children, validate they match the group's is_timeseries

        Raises:
            ValueError: If child's is_timeseries doesn't match the group's existing value
        """
        # Only validate for leaf children (attribute nodes)
        if child.schemanode_type != SchemaNodeType.GROUP:
            if self.is_timeseries is None:
                # First child sets the group's is_timeseries
                self.is_timeseries = child.is_timeseries
            elif self.is_timeseries != child.is_timeseries:
                # Subsequent children must match
                raise ValueError(
                    f"Cannot add child '{child.schemanode_name}' (is_timeseries={child.is_timeseries}) "
                    f"to group '{self.schemanode_name}' (is_timeseries={self.is_timeseries}). "
                    f"All attributes in a group must have the same is_timeseries value."
                )

        self.children_nodes.append(child)

    def set_schema(self, schema: "Schema") -> None:
        """
        Set the parent schema reference for this node.
        """
        self.schema = schema

    def upload(self) -> None:
        """
        Upload this schema node to the configured datamodeler.
        """
        if self.ignore_upload:
            return
        if self.db_schemanode_id:
            return
        if self.schema is None:
            raise ValueError("Schemanode has no associated schema. Cannot upload.")
        if self.schema.global_server_url is None:
            raise ValueError("Global server URL is not set, use set_global_server_url() before uploading anything.")

        if self.parent_node:
            if self.parent_node.schemanode_id not in self.schema.schemanodes:
                raise ValueError(f"Parent schemanode {self.parent_node.schemanode_id} not found")
            if not self.parent_node.db_schemanode_id:
                raise ValueError(f"Parent schemanode {self.parent_node.schemanode_name} not uploaded yet")

        for ontology in self.schemanode_ontology:
            if not ontology.db_ontology_id:
                raise ValueError(f"Ontology {ontology.ontology_name} not uploaded yet")

        for datatype in self.schemanode_datatype:
            if not datatype.db_datatype_id:
                raise ValueError(f"Datatype {datatype.name} not uploaded yet")

        # Root node upload uses a dedicated endpoint.
        if self.parent_node is None:
            if self.schema.root_set:
                raise ValueError("Root node is already set but adding another root node")

            url = self.schema.global_server_url + "/schemas/head/"
            body_dict = {
                "name": self.schemanode_name,
                "description": self.schemanode_desc,
                "type": "ROOT",
            }
            response = requests.post(url, json=body_dict, timeout=60)
            if response.status_code in [201, 409]:
                response_dict = response.json()
                if "id" not in response_dict:
                    raise ValueError(
                        f"Failed to upload root schemanode {self.schemanode_name}: {response.text}, could not find id in response"
                    )
                self.db_schemanode_id = response_dict["id"]
                self.schema.root_set = True
                return
            raise ValueError(f"Failed to upload root schemanode {self.schemanode_name}: {response.text}")

        if not self.schema.root_set:
            raise ValueError("Root node is not set but adding non root node")

        if len(self.schemanode_datatype) > 1:
            raise ValueError("Schemanode has multiple datatypes, this is not supported yet")
        if len(self.schemanode_ontology) > 1:
            raise ValueError("Schemanode has multiple ontologies, this is not supported yet")

        url = self.schema.global_server_url + "/schemas/"
        body_dict = {
            "name": self.schemanode_name,
            "description": self.schemanode_desc,
            "type": self.schemanode_type.name,
            "dataTypeId": self.schemanode_datatype[0].db_datatype_id if len(self.schemanode_datatype) > 0 else None,
            "ontologyId": self.schemanode_ontology[0].db_ontology_id if len(self.schemanode_ontology) > 0 else None,
            "parentId": self.parent_node.db_schemanode_id if self.parent_node else None,
        }
        print(f"Uploading schemanode {self.schemanode_name} ({self.schemanode_type.name})")
        response = requests.post(url, json=body_dict, timeout=60)
        if response.status_code in [201, 409]:
            response_dict = response.json()
            if "id" not in response_dict:
                raise ValueError(
                    f"Failed to upload schemanode {self.schemanode_name}: {response.text}, could not find id in response"
                )
            self.db_schemanode_id = response_dict["id"]
            return

        raise ValueError(f"Failed to upload schemanode {self.schemanode_name}: {response.text}")
