from typing import List, Union, Dict, Any, Callable, Iterator, Optional, Tuple
import uuid
import random

from .OntologyNode import OntologyNode
from .DatatypeNode import DatatypeNode, DataTypeEnum
from .SchemaNode import SchemaNode
from .SchemaNodeType import SchemaNodeType

class Schema:
    """
    Contains a whole schema including the schemanodes, ontologies and datatypes as well as the
    schemas structure and the relevant URL to upload the schema to the datamodeler API.
    This class should be filled iteratively:
    1. Add Ontology nodes starting always with their root node. OntologyNode objects always
        manage their parents children list themselves.
    2. Add Datatype nodes, these are not hierarchical and can be added in any order.
    3. Add Schema nodes, again always starting with the root node.

    After adding everything, the following methods can be used:
    - `upload_schemanodes`: Uploads all schemanodes to the datamodeler API. Also uploads the
        ontologies and datatypes if they are not already uploaded.
    - `upload_ontologies`: Uploads all ontologies to the datamodeler API.
    - `upload_datatypes`: Uploads all datatypes to the datamodeler API.
    - `generate_data`: Generates synthetic data for all schemanodes, ontologies and datatypes.
        This is done based on the Datatype.
    - 'iterate` methods: Different ways to iterate over the schemanodes, ontologies and datatypes:
        - `iterate_schemanodes_breadth_first_search`: Iterates over all schemanodes
            using a breadth-first search.
        - `iterate_schemanodes_breadth_first_search_only_complete_nodes`: Iterates over all
            schemanodes using a breadth-first search, but only returns valid nodes,
            so attribute nodes with a connected ontology and
            datatype node or group nodes with any child.
        - `iterate_schemanodes_depth_first_search`: Iterates over all schemanodes
            using a depth-first search.
        - `iterate_ontologies_breadth_first_search`: Iterates over all ontologynodes
            using a breadth-first search.
        - `iterate_ontologies_depth_first_search`: Iterates over all ontologynodes
            using a depth-first search.
        - `iterate_datatypes`: Iterates over all datatypenodes.
    - `get_global_schema_id`: Returns the global schema ID. Throws an error if
        the schema is not uploaded yet.
    """
    schemanodes: Dict[str, SchemaNode]
        # A dictionary mapping the schemanode_id to the SchemanodeInfo
    ontologynodes: Dict[str, OntologyNode]
        # A dictionary mapping the ontology_id to the OntologyInfo
    datatypenodes: Dict[str, DatatypeNode]
        # A dictionary mapping the datatype_id to the DatatypeInfo
        # datatypes don't have a hierarchy TODO: support adding of transformations between datatypes
    depth2schemanodes: Dict[int, List[SchemaNode]]
        # A dictionary mapping the depth to the schemanodes at that depth
    depth2ontologynodes: Dict[int, List[OntologyNode]]
        # A dictionary mapping the depth to the ontologies at that depth
    # datatype nodes have no hierarchy, so no depth mapping
    global_server_url: Optional[str]
        # The url of the datamodeler API saving the schema, the ontologies
        # and the datatypes.
    id2schemanode: Dict[str, SchemaNode]
        # A dictionary mapping the id to the SchemanodeInfo

    def __init__(self) -> None:
        """
        The class gets initialized with empty dictionaries
        """
        self.schemanodes = {}
        self.ontologynodes = {}
        self.datatypenodes = {}
        self.depth2schemanodes = {}
        self.depth2ontologynodes = {}
        self.global_server_url = None
            # must be set before using the API
        self.schema_uploaded = False
        self.schema_was_uploaded = False
        self.root_set: bool = False
        self.uploaded_schemanodes: List[SchemaNode] = []
        self.uploaded_ontologies: List[OntologyNode] = []
        self.uploaded_datatypes: List[DatatypeNode] = []
        self.id2schemanode: Dict[str, SchemaNode] = {}

    def add_ontology_attributes(self,
                                    ontology_name: str,
                                    ontology_desc: str = "",
                                    parent_ontology: Union[OntologyNode, None] = None,
                                    ontology_id: Union[str, None] = None,
                                    children_ontologies: Union[List[OntologyNode], None] = None,
                                    db_ontology_id: Union[str, None] = None) -> OntologyNode:
        """
        Adds a new ontologynode given the relevant attributes and returns the id
        The id is either the given ontology_id or a newly generated UUID4
        Args:
            ontology_system: str
                The ontology system, e.g. snomed, loinc, etc.
            ontology_id: Union[str, None]
                The id of the ontology in the ontology system
            ontology_name: str
                The name of the ontology
            ontology_desc: str
                The description of the ontology, Optional
            parent_ontology: Union[OntologyInfo, None]
                The parent ontology, if None we are at root level
            children_ontologies: Union[List[OntologyInfo], None]
                The children ontologies, if empty we are at a leaf node
                This is automatically filled for the parent ontology of the
                ontology added with this method
            db_ontology_id: Union[str, None]
                The id of the ontology in the database, Optional
        """
        ## Create the OntologyInfo object
        # generate a new id if none is given
        if ontology_id is None:
            ontology_id = str(uuid.uuid4())
        if children_ontologies is None:
            children_ontologies = []

        # Validate that children ontologies exist in the schema
        for child_ontology in children_ontologies:
            if child_ontology.ontology_id not in self.ontologynodes:
                raise ValueError(f"Child ontology with id {child_ontology.ontology_id} does not exist")

        # create the object
        ontology = OntologyNode(ontology_id=ontology_id,
                                ontology_name=ontology_name,
                                ontology_desc=ontology_desc,
                                parent_ontology=parent_ontology,
                                children_ontologies=children_ontologies,
                                db_ontology_id=db_ontology_id,
                                schema=self)

        ## Add the OntologyInfo object and update dependencies
        if ontology_id in self.ontologynodes:
            #print(f"WARNING: Ontology already known: {ontology_name}")
            # nothing to do, the ontology is already known
            return self.ontologynodes[ontology_id]

        self.ontologynodes[ontology_id] = ontology

        # update the parent ontology to know about it's new child
        if parent_ontology is not None:
            parent_ontology.children_ontologies.append(ontology)

        # Add to depth mappings
        if ontology.depth not in self.depth2ontologynodes:
            self.depth2ontologynodes[ontology.depth] = [ontology]
        else:
            self.depth2ontologynodes[ontology.depth].append(ontology)

        # finally return the id
        return ontology

    def add_ontology_class(self, ontology: OntologyNode) -> str:
        """
        Adds the given OntologyInfo class and ensures all dependencies are
        fixed, e.g. fixing it's parents children list
        """
        # checks
        if ontology.ontology_id in self.ontologynodes:
            #print(f"WARNING: Ontology already known: {ontology.ontology_name}")
            return ontology.ontology_id

        for child_ontology in ontology.children_ontologies:
            if child_ontology.ontology_id not in self.ontologynodes:
                raise ValueError(f"Child ontology with id {child_ontology.ontology_id} does not exist")

        # add the ontology
        ontology_id = ontology.ontology_id
        if ontology_id in self.ontologynodes:
            raise ValueError(f"Ontology already known: {ontology.ontology_name}")

        # actual addition
        ontology.set_schema(self)
        self.ontologynodes[ontology.ontology_id] = ontology

        # update the parent ontology to know about its new child
        if ontology.parent_ontology is not None:
            ontology.parent_ontology.children_ontologies.append(ontology)

        if ontology.depth not in self.depth2ontologynodes:
            self.depth2ontologynodes[ontology.depth] = [ontology]
        else:
            self.depth2ontologynodes[ontology.depth].append(ontology)

        return ontology_id

    def add_datatype_attributes(self,
                                datatype_type: DataTypeEnum,
                                allowed_values: Optional[List[Any]] = None,
                                null_values: Optional[List[Any]] = None,
                                datatype_id: Union[str, None] = None,
                                name: str = "",
                                desc: str = "",
                                db_datatype_id: str = "",
                                custom_generation_function: Optional[Callable] = None,
                                validations: Optional[List[Dict]] = None,
                                raw_validation_string: Optional[str] = None) -> DatatypeNode:
        """
        Add a datatye given the relevant attributes and returns the id
        if no id is given, generates a UUID.
        For more information about the args check the relevant DatatypeInfo class
        """
        if not datatype_id:
            datatype_id = str(uuid.uuid4())
        if datatype_id in self.datatypenodes:
            #print(f"WARNING: Datatype {name} exists already")
            return self.datatypenodes[datatype_id]

        datatype = DatatypeNode(datatype_id=datatype_id,
                                                        datatype=datatype_type,
                                                        allowed_values=allowed_values,
                                                        null_values=null_values,
                                                        name=name,
                                                        desc=desc,
                                                        db_datatype_id=db_datatype_id,
                                                        custom_generation_function=custom_generation_function,
                                                        schema=self,
                                                        validations=validations,
                                                        raw_validation_string=raw_validation_string)
        self.datatypenodes[datatype_id] = datatype
        return datatype

    def add_datatype_class(self, datatype: DatatypeNode) -> str:
        """
        Adds the given DatatypeInfo class and returns the datatype_id
        """
        datatype_id = datatype.datatype_id
        if datatype_id in self.datatypenodes:
            #print(f"WARNING: Datatype {datatype.name} exists already")
            return datatype_id
        datatype.set_schema(self)
        self.datatypenodes[datatype_id] = datatype
        return datatype_id

    def add_schemanode_attributes(self,
                                  schemanode_dataypes: List[DatatypeNode],
                                  schemanode_ontologies: List[OntologyNode],
                                  corresponding_column_name: str,
                                  parent_schemanode: Union[SchemaNode, None] = None,
                                  children_schemanodes: Union[List[SchemaNode], None] = None,
                                  schemanode_id: Union[str, None] = None,
                                  schemanode_name: str = "",
                                  schemanode_desc: str = "",
                                  schemanode_type: SchemaNodeType = SchemaNodeType.ATOMIC_ATTRIBUTE,
                                  db_schemanode_id: Union[str, None] = None,
                                  is_timeseries: Optional[bool] = None) -> SchemaNode:
        """
        Adds a new schemanode given the relevant attributes and returns the id
        of the schemanode
        The id is generated if it is not given/None
        Automatically updates it's parentnodes children list
        """
        ## Ensure all given data makes sense/exists
        if not schemanode_id:
            schemanode_id = str(uuid.uuid4())
        if children_schemanodes is None:
            children_schemanodes = []
        # node exists already?
        if schemanode_id in self.schemanodes:
            raise ValueError(f"Schemanode with id {schemanode_id} already exists")
        # parent exists?
        if parent_schemanode is not None:
            if parent_schemanode.schemanode_id not in self.schemanodes:
                raise ValueError(f"Parent schemanode with id {parent_schemanode.schemanode_id} does not exist")
        # datatypes exist?
        for datatype in schemanode_dataypes:
            if datatype.datatype_id not in self.datatypenodes:
                # add it
                self.add_datatype_class(datatype)
        # ontology exist?
        for ontology in schemanode_ontologies:
            if ontology.ontology_id not in self.ontologynodes:
                self.add_ontology_class(ontology)
        # children exist?
        for children_schemanode in children_schemanodes:
            if children_schemanode.schemanode_id not in self.schemanodes:
                raise ValueError(f"Child schemanode with id {children_schemanode.schemanode_id} does not exist")

        ## Create the SchemanodeInfo object
        schemanode = SchemaNode(
            schemanode_id=schemanode_id,
            schemanode_name=schemanode_name,
            schemanode_desc=schemanode_desc,
            schemanode_type=schemanode_type,
            schemanode_datatype=schemanode_dataypes,
            schemanode_ontology=schemanode_ontologies,
            corresponding_column_name=corresponding_column_name,
            parent_node=parent_schemanode,
            children_nodes=children_schemanodes,
            db_schemanode_id=db_schemanode_id,
            is_timeseries=is_timeseries,
            schema=self
        )

        ## update the parent node and get the depth from the schemanode itself
        if parent_schemanode is not None:
            parent_schemanode.add_child(schemanode)

        ## Add the schemanode to the relevant dicts
        self.schemanodes[schemanode_id] = schemanode
        if schemanode.depth not in self.depth2schemanodes:
            self.depth2schemanodes[schemanode.depth] = [schemanode]
        else:
            self.depth2schemanodes[schemanode.depth].append(schemanode)
        self.id2schemanode[schemanode_id] = schemanode

        return schemanode

    def upload_ontologies(self) -> None:
        """
        Uploads the ontologies to the global server.
        If an ontology already exists (409 conflict), uses the existing one.
        """
        # we iterate with breadth first search, so we can be sure
        # that the parent ontologies are already uploaded
        for ontology in self.iterate_ontologies_breadth_first_search():
            # if the ontology already has a db_id, skip it
            if ontology.db_ontology_id:
                continue

            # we need to check if the parent ontology is already uploaded
            if ontology.parent_ontology:
                parent_ontology = ontology.parent_ontology
                if not parent_ontology.db_ontology_id:
                    print(f"ERROR while trying to upload {ontology.ontology_name}")
                    print(f"Parent ontology {parent_ontology.ontology_name} not uploaded yet")
                    print(f"Parent ontology has id {parent_ontology.ontology_id} and db_id {parent_ontology.db_ontology_id}")
                    raise ValueError(f"Parent ontology {parent_ontology.ontology_name} not uploaded yet")

            # parent exists, self does not yet have db_id, attempt upload
            ontology.upload()
            self.uploaded_ontologies.append(ontology)

    def upload_datatypes(self) -> None:
        """
        Uploads the datatypes to the global server.
        If a datatype already exists (409 conflict), uses the existing one.
        """
        for datatype in self.datatypenodes.values():
            # if the datatype already has a db_id, skip it
            if datatype.db_datatype_id:
                continue

            # datatype does not yet have db_id, upload the datatype
            datatype.upload()
            self.uploaded_datatypes.append(datatype)

    def upload_schemanodes(self,
                           skip_ontology_upload:bool=False,
                           skip_datatype_upload:bool=False,
                           only_upload_complete_schema:bool=False) -> None:
        """
        Uploads the schemanodes to the global server.
        It skips any schemanode that is already uploaded
        (whose schemanode_id or db_schemanode_id exists in the db).
        if not specified otherwise by the function parameters, it also
        uploads the ontologies and datatypes.
        Args:
            skip_ontology_upload: bool
                If True, the ontologies are not uploaded in this function.
                Use if you already called upload_ontologies()
            skip_datatype_upload: bool
                If True, the datatypes are not uploaded in this function.
                Use if you already called upload_datatypes()
            only_upload_complete_schema: bool
                If True, only nodes attribute schema nodes that have a connected
                ontology and datatype are uploaded. Group nodes are uploaded
                only if they have at least one child node with a datatype and
                ontology.
        """
        # just to be sure we try to upload the datatypes and ontologies
        # again
        if not skip_datatype_upload:
            self.upload_datatypes()
        if not skip_ontology_upload:
            self.upload_ontologies()
        # root state is tracked on the schema itself
        root_nodes = self.depth2schemanodes.get(0, [])
        self.root_set = bool(root_nodes and root_nodes[0].db_schemanode_id)

        # breadth first search to ensure that the parent nodes are uploaded
        if not only_upload_complete_schema:
            for schemanode in self.iterate_schemanodes_breadth_first_search():
                schemanode.upload()
                self.uploaded_schemanodes.append(schemanode)
        else:
            # we use a different iterator to only upload nodes that have
            # a connected ontology and datatype
            for schemanode in self.iterate_schemanodes_breadth_first_search_only_complete_nodes():
                schemanode.upload()
                self.uploaded_schemanodes.append(schemanode)
        self.schema_uploaded = True

    def generate_data_for_schemanode(self,
                                     schemanode: SchemaNode,
                                     num_values: int,
                                     missing_ratio: float) -> Optional[List[Any]]:
        """
        Generate the data for a single `schemanode` and return it as a list.
        Returns None for group nodes (no column generated).
        """
        # group nodes: nothing to generate
        if len(schemanode.children_nodes) > 0:
            if len(schemanode.schemanode_datatype) > 0:
                raise ValueError(f"Group node {schemanode.schemanode_name} has datatypes, this is not allowed")
            return None

        # validate column name
        colname = schemanode.schemanode_name
        if not colname:
            raise ValueError("Schemanode has no name but trying to generate data for it")

        # get the datatypes
        datatypes = schemanode.schemanode_datatype
        if len(datatypes) == 0:
            raise ValueError(f"Schema node {schemanode.schemanode_name} has no datatypes")

        # single datatype: generate all values from it
        if len(datatypes) == 1:
            datatype = datatypes[0]
            return self._generate_data_from_datatype(datatype, num_values, missing_ratio)

        # multiple datatypes: split evenly and fill leftover with a random datatype
        chunksize = num_values // len(datatypes)
        leftover = num_values - (chunksize * len(datatypes))
        data: List[Any] = []
        for datatype in datatypes:
            tmp = self._generate_data_from_datatype(datatype, chunksize, missing_ratio)
            data.extend(tmp)

        datatype = random.choice(datatypes)
        tmp = self._generate_data_from_datatype(datatype, leftover, missing_ratio)
        data.extend(tmp)

        if len(data) != num_values:
            raise ValueError(f"Data generation failed for {schemanode.schemanode_name}")
        return data


    def _generate_data_from_datatype(self, datatype: DatatypeNode,
                                     num_values:int,
                                     missing_ratio:float) -> List[Any]:
        """
        Generates data from a single datatype, checks whether the datatype
        has null_values and uses the missing_ratio or dirty_missing_ratio
        accordingly.
        """
        data = datatype.generate_data(n=num_values, missing_ratio=missing_ratio)
        if not data:
            raise ValueError(f"Data generation failed for {datatype.name}")
        return data

    def iterate_schemanodes_breadth_first_search(self) -> Iterator[SchemaNode]:
        """
        Iterates over all schemanodes, breadth first search, so therefore
        starts at root level and then goes through the schema level by level.
        """
        for depth in sorted(self.depth2schemanodes.keys()):
            for schemanode in self.depth2schemanodes[depth]:
                yield schemanode

    def iterate_schemanodes_breadth_first_search_only_complete_nodes(self) -> Iterator[SchemaNode]:
        """
        Iterates over all schemanodes, breadth first search, so therefore
        starts at root level and then goes through the schema level by level.
        Only returns attribute nodes that have a connected ontology and datatype
        and group nodes that have at least one child/grandchild/... node with
        a datatype and ontology.
        """
        # recursive solution, could be improved by going from leaf to top saving
        # an has_children_with_datatype_and_ontology flag
        for depth in sorted(self.depth2schemanodes.keys()):
            for schemanode in self.depth2schemanodes[depth]:
                if schemanode.schemanode_datatype and schemanode.schemanode_ontology:
                    yield schemanode
                elif not schemanode.schemanode_datatype and not schemanode.schemanode_ontology:
                    # potential group node, check if at least one child/grandchild/... has a datatype and ontology
                    has_child = False
                    for descendantNode in self._schemanode_dfs_internal(schemanode):
                        if descendantNode.schemanode_datatype and descendantNode.schemanode_ontology:
                            has_child = True
                            break
                    if has_child:
                        yield schemanode


    def iterate_schemanodes_depth_first_search(self) -> Iterator[SchemaNode]:
        """
        Iterates over all schemanodes, depth first search, so therefore
        starts at root level and follows the first child until a leaf node
        is reached, then continues with the next child of root, ...
        """
        for root_level_node in self.depth2schemanodes[0]:
            yield from self._schemanode_dfs_internal(root_level_node)

    def get_schemanode_by_id(self, schemanode_id: str) -> Optional[SchemaNode]:
        """
        Returns the schemanode with the given ID, or None if it doesn't exist.
        """
        return self.id2schemanode.get(schemanode_id, None)

    def _schemanode_dfs_internal(self, node:SchemaNode) -> Iterator[SchemaNode]:
        """
        Internal function to iterate over the schemanodes depth first search
        Recursive function
        """
        yield node
        for child_node in node.children_nodes:
            yield from self._schemanode_dfs_internal(child_node)

    def iterate_ontologies_breadth_first_search(self) -> Iterator[OntologyNode]:
        """
        Iterates over all ontologies, breadth first search, so therefore
        starts at root level and then goes through the ontology level by level.
        """
        for depth in sorted(self.depth2ontologynodes.keys()):
            for ontology in self.depth2ontologynodes[depth]:
                yield ontology

    def iterate_ontologies_depth_first_search(self) -> Iterator[OntologyNode]:
        """
        Iterates over all ontologies, depth first search, so therefore
        starts at root level and follows the first child until a leaf node
        is reached, then continues with the next child of root, ...
        """
        for root_level_node in self.depth2ontologynodes[0]:
            yield from self._ontology_dfs_internal(root_level_node)

    def _ontology_dfs_internal(self, node: OntologyNode) -> Iterator[OntologyNode]:
        """
        Internal function to iterate over the ontologies depth first search
        Recursive function
        """
        yield node
        for child_ontology in node.children_ontologies:
            yield from self._ontology_dfs_internal(child_ontology)

    def iterate_datatypes(self) -> Iterator[DatatypeNode]:
        """
        Iterates over all datatypes
        """
        for datatype in self.datatypenodes.values():
            yield datatype

    def set_datamodeler_url(self, url:str) -> None:
        """
        Sets the global server url
        """
        self.global_server_url = url


    def get_global_schema_id(self) -> str:
        """
        Returns the global schema id
        Throws an error if the current schema state is different then the uploaded state.
        """
        if not self.schema_uploaded:
            print("WARNING: Schema has not been uploaded yet. Assuming it has been uploaded in some previous run/another instance.")
            self.schema_was_uploaded = True
        else:
            self.schema_was_uploaded = True
            if len(self.uploaded_schemanodes) != len(self.schemanodes):
                raise ValueError("Uploaded schemanodes do not match the current schema. Please upload the schema first.")
            for schemanode in self.uploaded_schemanodes:
                if schemanode.schemanode_id not in self.schemanodes:
                    raise ValueError(f"Schema node {schemanode.schemanode_name} with id {schemanode.schemanode_id} not found in the current schema. Uploaded schema might be different from the current schema.")
            if len(self.uploaded_datatypes) != len(self.datatypenodes):
                raise ValueError("Uploaded datatypes do not match the current schema. Please upload the schema first.")
            for datatype in self.uploaded_datatypes:
                if datatype.datatype_id not in self.datatypenodes:
                    raise ValueError(f"Datatype {datatype.name} with id {datatype.datatype_id} not found in the current schema. Uploaded schema might be different from the current schema.")
            if len(self.uploaded_ontologies) != len(self.ontologynodes):
                raise ValueError("Uploaded ontologies do not match the current schema. Please upload the schema first.")
            for ontology in self.uploaded_ontologies:
                if ontology.ontology_id not in self.ontologynodes:
                    raise ValueError(f"Ontology {ontology.ontology_name} with id {ontology.ontology_id} not found in the current schema. Uploaded schema might be different from the current schema.")

        # we're fine, we can just return the schemanode of depth 0s id
        if len(self.depth2schemanodes[0]) != 1:
            raise ValueError("There should be exactly one root node in the schema")
        root_schemanode = self.depth2schemanodes[0][0]
        if root_schemanode.db_schemanode_id:
            return root_schemanode.db_schemanode_id
        else:
            return root_schemanode.schemanode_id

    def get_group_and_attribute_nodes(self) -> Tuple[List[SchemaNode], List[SchemaNode]]:
        """
        Returns all group nodes (nodes with children) and attribute nodes (leaf nodes with no children).

        Returns:
            tuple: (group_nodes, attribute_nodes)
                - group_nodes: List of SchemaNode objects that have children
                - attribute_nodes: List of SchemaNode objects that are leaf nodes (no children)
        """
        group_nodes: List[SchemaNode] = []
        attribute_nodes: List[SchemaNode] = []

        for schemanode in self.schemanodes.values():
            if len(schemanode.children_nodes) > 0:
                # This is a group node (has children)
                group_nodes.append(schemanode)
            else:
                # This is an attribute/leaf node (no children)
                attribute_nodes.append(schemanode)

        return group_nodes, attribute_nodes
