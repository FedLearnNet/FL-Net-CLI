from typing import Dict, Optional, List, Any
import random
from datetime import datetime, timedelta, timezone
import os
import pandas as pd
import requests
from .Schema import Schema
from .SchemaNode import SchemaNode
from .SchemaNodeType import SchemaNodeType
from .DatatypeNode import DataTypeEnum
from .util import DataEntry, request_with_renew_auth
from .auth import get_auth_headers
from .cohort import get_or_create_cohort
from . import custom_data_generators

# Default column names for the schema info CSV
DEFAULT_SCHEMA_NODE_NAME_COL = "schemanode_name"
DEFAULT_SCHEMA_NODE_DESCRIPTION_COL = "schemanode_description"
DEFAULT_SCHEMA_NODE_TYPE_COL = "schemanode_type"
DEFAULT_ONTOLOGY_ID_COL = "ontology_id"
DEFAULT_ONTOLOGY_NAME_COL = "ontology_name"
DEFAULT_DATATYPE_NAME_COL = "datatype_name"
DEFAULT_DATATYPE_TYPE_COL = "datatype_type"
DEFAULT_DATATYPE_ALLOWED_VALUES_COL = "allowed_values"
DEFAULT_IS_TIMESERIES_COL = "is_timeseries"
DEFAULT_GROUP_NODE_COL = "group_node"
DEFAULT_CUSTOM_GEN_FUNC_COL = "customdatagenerationfunction"
DEFAULT_DATATYPE_DESCRIPTION_COL = "datatype_description"
DEFAULT_PATIENT_ID_COL = "Code"

class FedDBDataInstance:
    """
    A class that represents all information about one federated database instance.
    Used to programatically set up a node.

    This class now reads and builds a SINGLE schema (no input/output split). The schema
    columns are explicitly provided in the __init__ signature, so the info file only
    needs to contain the columns you intend to use.

    The following methods are available (and should be called in this order):
        - create_data: Creates data based on the configured schema. This can be used at
            any time to generate new data.
        - upload_schema: Uploads the schema to the global datamodeler.
            This is necessary to create a cohort.
        - create_upload_cohort: Creates a cohort with the given name and description and uploads it
            to the local learning API.
    """
    schema: Schema
    patient_id_col_name: str
        # used in data generation to set the column name of the patient id column

    def __init__(self,
                 info_filename: str,
                 schemanode_name_col: str = DEFAULT_SCHEMA_NODE_NAME_COL,
                 schemanode_description_col: Optional[str] = DEFAULT_SCHEMA_NODE_DESCRIPTION_COL,
                 schemanode_type_col: Optional[str] = DEFAULT_SCHEMA_NODE_TYPE_COL,
                 ontology_name_col: str = DEFAULT_ONTOLOGY_NAME_COL,
                 datatype_name_col: str = DEFAULT_DATATYPE_NAME_COL,
                 datatype_type_col: str = DEFAULT_DATATYPE_TYPE_COL,
                 datatype_allowed_values_col: str = DEFAULT_DATATYPE_ALLOWED_VALUES_COL,
                 is_timeseries_colname: str = DEFAULT_IS_TIMESERIES_COL,
                 group_node_col: Optional[str] = DEFAULT_GROUP_NODE_COL,
                 ontology_id_col: Optional[str] = DEFAULT_ONTOLOGY_ID_COL,
                 custom_generation_function_col: Optional[str] = DEFAULT_CUSTOM_GEN_FUNC_COL,
                 patient_id_col_name: str = DEFAULT_PATIENT_ID_COL,
                 datatype_description_col: Optional[str] = DEFAULT_DATATYPE_DESCRIPTION_COL,
                 schema_name: Optional[str] = None,
) -> None:
        """
        Loads the schema.
        Does not interact/upload anything yet.
        """
        # setup of vars
        self.__required_cols = [
            col for col in [
                schemanode_name_col,
                schemanode_description_col,
                schemanode_type_col,
                ontology_id_col,
                ontology_name_col,
                datatype_name_col,
                datatype_type_col,
                datatype_allowed_values_col,
                group_node_col,
            ]
            if col is not None
        ]
        self.datatype_description_col = datatype_description_col
        self.__default_null_values = [None]
        self.__cohort_id: Optional[int] = None
        self.patient_id_col_name = patient_id_col_name

        # read input
        self.__read_file(info_filename)

        # schema creation
        if schema_name is None:
            schema_name = os.path.basename(info_filename).split(".")[0]
        print("Creating schema")
        self.schema = self.__create_schema(
            ontology_name_col=ontology_name_col,
            datatype_name_col=datatype_name_col,
            datatype_type_col=datatype_type_col,
            datatype_allowed_values_col=datatype_allowed_values_col,
            is_timeseries_colname=is_timeseries_colname,
            group_node_col=group_node_col,
            ontology_id_col=ontology_id_col,
            schemanode_name_col=schemanode_name_col,
            schemanode_description_col=schemanode_description_col,
            schemanode_type_col=schemanode_type_col,
            custom_generation_function_col=custom_generation_function_col,
            schema_name=schema_name
        )
        if self.schema is None:
            raise ValueError("Schema could not be created. Check the info file.")

        # Backwards-compatible aliases (single schema)
        self.input_schema = self.schema
        self.output_schema = self.schema

    def create_input_data(self, num_patients: int = 1000, missing_ratio: float = 0.1,
                          max_entries_timeseries: int = 5,
                          timestamp_formats: Optional[List[str]] = None) -> pd.DataFrame:
        """Compatibility wrapper for create_data (single schema)."""
        return self.create_data(num_patients=num_patients, missing_ratio=missing_ratio,
                                max_entries_timeseries=max_entries_timeseries,
                                timestamp_formats=timestamp_formats)

    def create_harmonized_data(self, num_patients: int = 1000, missing_ratio: float = 0.1,
                               max_entries_timeseries: int = 5,
                               timestamp_formats: Optional[List[str]] = None) -> pd.DataFrame:
        """Compatibility wrapper for create_data (single schema)."""
        return self.create_data(num_patients=num_patients, missing_ratio=missing_ratio,
                                max_entries_timeseries=max_entries_timeseries,
                                timestamp_formats=timestamp_formats)

    def create_data(self, num_patients: int = 1000,
                    missing_ratio: float = 0.1, max_entries_timeseries: int = 5,
                    timestamp_formats: Optional[List[str]] = None) -> pd.DataFrame:
        """
        Data creation function for the single configured schema.
        If the schema contains group nodes (including the root), this function will
        generate additional timeseries columns per group:
          <group_name>_timestamp and <group_name>_visitId

        Procedure (summary):
        - Generate base attribute data using the requested schema for `num_patients` patients.
        - Create patient assignment and timeseries/visit ids distributed across patients.
        - Each patient gets data for all timeseries groups with between 1 and
          `max_entries_timeseries` entries per timeseries attribute.

        Args:
            num_patients: Number of patients to generate data for
            missing_ratio: Ratio of missing values in the generated data
            max_entries_timeseries: Maximum number of timeseries entries per attribute per patient
            timestamp_formats: List of timestamp format strings to use. If None, defaults to ['%Y-%m-%dT%H:%M:%SZ']
    """
        patient_id2group_node2DataEntriesRows = self.__generate_patient_rows_and_group_entries(
            schema=self.schema,
            num_patients=num_patients,
            missing_ratio=missing_ratio,
            max_entries_timeseries=max_entries_timeseries,
            timestamp_formats=timestamp_formats
        )

        # Build rows as dictionaries
        all_rows: List[Dict[str, Any]] = []
        for patient_id, group_node2DataEntriesRows in patient_id2group_node2DataEntriesRows.items():
            # Determine how many rows this patient needs (max across all groups)
            max_rows_for_patient = max(len(data_entries_rows) for data_entries_rows in group_node2DataEntriesRows.values())

            # Create rows for this patient
            for row_idx in range(max_rows_for_patient):
                row_dict: Dict[str, Any] = {self.patient_id_col_name: patient_id}

                # Fill in data from each group
                for group_node, data_entries_rows in group_node2DataEntriesRows.items():
                    if row_idx < len(data_entries_rows):
                        data_entries_row = data_entries_rows[row_idx]

                        for data_entry in data_entries_row:
                            # Add the attribute value
                            row_dict[data_entry.corresponding_schemanode.schemanode_name] = data_entry.value

                            # Add timestamp and visit_id for this group
                            if data_entry.timestamp is not None or data_entry.visit_id is not None:
                                row_dict[f"{group_node.schemanode_id}_group_visit_timestamp"] = data_entry.timestamp
                                row_dict[f"{group_node.schemanode_id}_group_visit_id"] = data_entry.visit_id

                all_rows.append(row_dict)

        # Create dataframe from list of dictionaries
        df = pd.DataFrame(all_rows)

        # Ensure patient_id is the first column
        if self.patient_id_col_name in df.columns:
            cols = [self.patient_id_col_name] + [col for col in df.columns if col != self.patient_id_col_name]
            df = df[cols]

        # Remove columns with only NaN values
        df = df.dropna(axis=1, how='all')
        # Remove rows with only NaN values
        df = df.dropna(axis=0, how='all')

        return df

    def upload_harmonized_data(self,
                               local_learning_api_url: str,
                               request_auth_headers: dict,
                               num_patients: int = 1000,
                               missing_ratio: float = 0.1,
                               max_entries_timeseries: int = 5,
                               timestamp_formats: Optional[List[str]] = None) -> None:
        """
        Uploads the harmonized data to the local learning API datamodeler.
        """
        if self.__cohort_id is None:
            raise ValueError("Cohort must be created and uploaded before uploading harmonized data.")

        # Ensure the cohort doesn't have data yet
        auth_headers = get_auth_headers(**request_auth_headers)
        response, auth_headers = request_with_renew_auth(
            method="GET",
            url=f"{local_learning_api_url}/cohort/{self.__cohort_id}",
            body=None,
            auth_headers=auth_headers,
            request_auth_headers=request_auth_headers
        )

        if response.status_code != 200:
            raise ValueError(f"Failed to retrieve patient data to check whether data addition is ok: {response.text}")

        num_patients_in_cohort = response.json().get("amountOfPatients", None)
        if num_patients_in_cohort is None:
            raise RuntimeError(f"Response of cohort did not contain information about how many patients exist already: {response.text}")
        if num_patients_in_cohort > 0:
            print(f"WARNING: the cohort already contains {num_patients_in_cohort} patients")
            if num_patients_in_cohort < num_patients:
                user_input = input("Do you want to continue the data upload? (y/n): ")
                if user_input.lower() != 'y':
                    print("Aborting data upload.")
                    return
                num_patients = num_patients - num_patients_in_cohort
            else:
                print("Cohort already has equal or more patients than the number of patients to upload. Aborting data upload.")
                return


        patient_id2group_node2DataEntriesRows = \
            self.__generate_patient_rows_and_group_entries(
                schema=self.schema,
                num_patients=num_patients,
                missing_ratio=missing_ratio,
                max_entries_timeseries=max_entries_timeseries,
                timestamp_formats=timestamp_formats)

        for patient_id, group_node2DataEntriesRows in patient_id2group_node2DataEntriesRows.items():
            # create the patient
            patient_number = int(patient_id.split("_")[-1])
            patient_id = f"patient_{patient_number+num_patients_in_cohort}"
            print(f"Creating patient {patient_id}")
            body_dict = {
                "cohortId": self.__cohort_id,
                "externalPatientId": patient_id,
                "dataEntries": []
            }
            response, auth_headers = request_with_renew_auth(
                method="POST",
                url=f"{local_learning_api_url}/cohort/{self.__cohort_id}/data/patient",
                body=body_dict,
                auth_headers=auth_headers,
                request_auth_headers=request_auth_headers
            )

            if response.status_code != 201:
                raise ValueError(f"Failed to create patient: {response.text}")

            internal_patient_id = response.json().get("id")
            if not internal_patient_id:
                raise ValueError(f"Failed to retrieve internal patient ID from response: {response.text}")
            # We upload row by row of each group node.
            for data_entries_rows in group_node2DataEntriesRows.values():
                # each entry is MULTIPLE rows!
                for data_entries_row in data_entries_rows:
                    # Each entry is MULTIPLE dataEntries of ONE row
                    data_entries_to_upload = []
                    for data_entry in data_entries_row:
                        # finally just one entry
                        if data_entry.visit_id is not None:
                            # Support new visit id format: visit_patient_{patientNum}_{timestampIdx}
                            # and fall back to the old format: visit_{n}
                            parts = str(data_entry.visit_id).split("_")
                            try:
                                if len(parts) >= 4 and parts[1] == 'patient':
                                    # visit_patient_{patientNum}_{timestampIdx}
                                    orig_patient_num = int(parts[-2])
                                    timestamp_idx = int(parts[-1])
                                    new_patient_num = orig_patient_num + num_patients_in_cohort
                                    data_entry.visit_id = f"visit_patient_{new_patient_num}_{timestamp_idx}"
                                else:
                                    # fallback: visit_{n}
                                    visit_number = int(parts[-1])
                                    data_entry.visit_id = f"visit_{visit_number + num_patients_in_cohort}"
                            except Exception:
                                # If parsing fails, keep the original visit id (best-effort)
                                pass
                        entry_to_upload = {
                            "schemaNodeId": data_entry.corresponding_schemanode.get_local_learning_schemanode_id(),
                            "visitId": data_entry.visit_id,
                            "visitTimestamp": data_entry.timestamp,
                            "visitTimestampFormat": "ISO_8601" if data_entry.timestamp is not None else None,
                            "value": data_entry.value,
                        }
                        data_entries_to_upload.append(entry_to_upload)

                    # Upload the one row of values of this group of values of this patient
                    response, auth_headers = request_with_renew_auth(
                        method="POST",
                        url=f"{local_learning_api_url}/cohort/{self.__cohort_id}/data/patient/{internal_patient_id}/dataentry/bulk",
                        body=data_entries_to_upload,
                        auth_headers=auth_headers,
                        request_auth_headers=request_auth_headers
                    )
                    if response.status_code != 201:
                        raise ValueError(f"Failed to upload data for patient {internal_patient_id}, cohort {self.__cohort_id}, dataentries {data_entries_to_upload}: {response.text} with status code {response.status_code}")

    def upload_output_schema(self, datamodeler_api_url: str) -> None:
        """
        Backwards-compatible name for upload_schema.
        """
        self.upload_schema(datamodeler_api_url=datamodeler_api_url)

    def upload_schema(self, datamodeler_api_url: str) -> None:
        """
        Uploads the schema to the global datamodeler.
        """
        self.schema.set_datamodeler_url(datamodeler_api_url)
        self.schema.upload_schemanodes()
            # This also uploads the ontologies and datatypes

    def use_uploaded_schema(self, datamodeler_api_url: str) -> None:
        """
        Looks up the schema in the global datamodeler without uploading anything.
        Use instead of upload_schema if the schema was uploaded in a previous run.
        """
        root_schemanode = self.schema.depth2schemanodes[0][0]
        response = requests.get(f"{datamodeler_api_url}/schemas/head", timeout=360)
        if response.status_code != 200:
            raise ValueError(f"Failed to get schemas from {datamodeler_api_url}: {response.text} with status code: {response.status_code}")
        for head in response.json():
            if head.get("name") == root_schemanode.schemanode_name:
                root_schemanode.db_schemanode_id = head.get("id")
                return
        raise ValueError(f"Schema {root_schemanode.schemanode_name} not found in {datamodeler_api_url}. Please upload the schema first.")

    def get_global_schema_id(self) -> str:
        """
        Returns the id of the schema in the global datamodeler.
        """
        return self.schema.get_global_schema_id()

    def create_upload_cohort(self, name: str, desc: str,
                             local_learning_api_url: str,
                             request_auth_headers: dict) -> None:
        """
        Creates a cohort with the given name and description and uploads it to the
        local learning API.
        Sets the __cohort_uploaded flag to True.
        """
        # Uses the existing cohort if one with that name exists already
        self.__cohort_id = get_or_create_cohort(
            local_learning_api_url=local_learning_api_url,
            name=name,
            desc=desc,
            global_schema_id=self.schema.get_global_schema_id(),
            request_auth_headers=request_auth_headers
        )

        auth_headers = get_auth_headers(**request_auth_headers)
        # request the cohort details to set the local_learning_schemanode_id
        response, auth_headers = request_with_renew_auth(
            method="GET",
            url=f"{local_learning_api_url}/cohort/{self.__cohort_id}",
            body=None,
            auth_headers=auth_headers,
            request_auth_headers=request_auth_headers
        )
        if response.status_code != 200:
            raise ValueError(f"Failed to get cohort details: {response.text} with status code: {response.status_code}")

        # we need to go through the response and set in the schema the internal schemanode IDs
        # as they are then later used for any interactions with the local learning API
        for schemanode in self._recursively_iterate_fields(response.json().get("schemaRoot", {})):
            # Get internal schema_node_id
            internal_schemanode_id = schemanode.get("id", None)
            if internal_schemanode_id is None:
                print(f"Got the following response: {response.json()}")
                raise ValueError(f"Schema node {schemanode} has no internal schema node ID.")

            # get global schemanode id
            global_schemanode_id = schemanode.get("globalId", None)
            if global_schemanode_id is None:
                raise ValueError(f"Schema node {schemanode} with id {internal_schemanode_id} has no global schema node ID.")

            # find the corresponding node
            schemanode = self.schema.get_schemanode_by_id(global_schemanode_id)
            if schemanode is None:
                raise ValueError(f"Schema node with global ID {global_schemanode_id} from cohort response not found.")

            schemanode.set_local_learning_schemanode_id(internal_schemanode_id)

    def _recursively_iterate_fields(self, cohort_create_response_schemanode):
        """
        Recursively iterates the fields attribute in a depth search approach
        """
        yield cohort_create_response_schemanode
        for child in cohort_create_response_schemanode.get("childNodes", []):
            yield from self._recursively_iterate_fields(child)

    def __read_file(self, info_filename: str) -> None:
        """
        Reads the schema informaton file
        """
        info_df = pd.read_csv(info_filename)
        missing_cols = [col for col in self.__required_cols if col not in info_df.columns]
        if missing_cols:
            raise ValueError(f"Missing required columns in info file: {missing_cols}")
        self.__info_df = info_df

    def __create_schema(self, ontology_name_col: str,
                         datatype_name_col: str, datatype_type_col: str,
                         datatype_allowed_values_col: str,
                         is_timeseries_colname: str,
                         group_node_col: Optional[str] = None,
                         ontology_id_col: Optional[str] = None,
                         schemanode_name_col: Optional[str] = None,
                         schemanode_description_col: Optional[str] = None,
                         schemanode_type_col: Optional[str] = None,
                         custom_generation_function_col: Optional[str] = None,
                         schema_name: str = "root",
                         datatype_description_col: Optional[str] = None) -> Schema:
        """
        Creates a schema using the information from the info_df. If the ontology_id_col is not
        provided, it just uses a UUID + the ontology_name as the ontologyID.
        Automatically creates a root node of the schema.
        If group_node_col is provided, it creates group nodes for each unique value in that column
        and adds the schema nodes as children of the group nodes.
        The ontology_name and id col may be comma seperated lists, in which case for each
        entry a schemanode is created. The datatype is then used for each one of these schema nodes.
        # TODO: fix the description
        """
        if self.__info_df is None or self.__info_df.empty:
            raise ValueError("Info DataFrame is empty or uninitialized. Cannot create schema.")
        schema = Schema()
            # datatype name -> DatatypeNode mapping
        # create the root node
        root_node = schema.add_schemanode_attributes(
            schemanode_dataypes=[],
            schemanode_ontologies=[],
            schemanode_id=schema_name,
            schemanode_name=schema_name,
            schemanode_type=SchemaNodeType.ROOT,
            schemanode_desc="Root node of the schema " + schema_name,
            corresponding_column_name="",
        )

        group_nodes: Dict[str, SchemaNode] = {}
        schemanode_ids = set()
        for idx, row in self.__info_df.iterrows():
            print(f"Processing row {idx} for ontology '{row[ontology_name_col]}' and datatype '{row[datatype_name_col]}'")
            # One schema node per row: no comma-splitting for schema/ontology/group columns.
            ontology_name = str(row[ontology_name_col]).strip() if pd.notna(row[ontology_name_col]) else ""
            if not ontology_name:
                raise ValueError(f"Row {idx}: Ontology name is missing or empty in column '{ontology_name_col}'.")
            ontology_id = (
                str(row[ontology_id_col]).strip()
                if ontology_id_col is not None and pd.notna(row[ontology_id_col])
                else ontology_name
            )
            if not ontology_id:
                ontology_id = ontology_name
            ontology_node = schema.add_ontology_attributes(
                ontology_name=ontology_name,
                ontology_id=ontology_id,
                ontology_desc=f"Ontology {ontology_name}"
            )

            # add and create the datatype node
            # TODO: support multiple datatypes per row
            datatype_name = str(row[datatype_name_col]).strip() if pd.notna(row[datatype_name_col]) else None
            if datatype_name is None:
                raise ValueError(f"Row {idx}: Datatype name is missing or empty. Please check the info file.")
            datatype_type = DataTypeEnum(row[datatype_type_col])
            datatype_id = f"{datatype_name}"
            raw_allowed_vals = row[datatype_allowed_values_col] if not pd.isna(row[datatype_allowed_values_col]) else None

            # Get custom generation function if specified
            custom_gen_func = None
            if custom_generation_function_col is not None and custom_generation_function_col in row and pd.notna(row[custom_generation_function_col]):
                func_name = str(row[custom_generation_function_col]).strip()
                if func_name:  # Not empty string
                    if hasattr(custom_data_generators, func_name):
                        custom_gen_func = getattr(custom_data_generators, func_name)
                    else:
                        raise ValueError(f"Row {idx}: Custom generation function '{func_name}' not found in custom_data_generators module.")

            # Determine datatype description if column is present
            datatype_desc = f"Datatype {datatype_name}"
            if datatype_description_col is not None and datatype_description_col in row and pd.notna(row[datatype_description_col]):
                parsed_datatype_desc = str(row[datatype_description_col]).strip()
                if parsed_datatype_desc:
                    datatype_desc = parsed_datatype_desc

            datatype_node = schema.add_datatype_attributes(
                name=datatype_name,
                desc=datatype_desc,
                datatype_type=datatype_type,
                datatype_id=datatype_id,
                null_values=self.__default_null_values,
                custom_generation_function=custom_gen_func,
                validations=None,
                raw_validation_string=raw_allowed_vals
            )

            # add and create the group schema node
            parent_node: SchemaNode = root_node
            if group_node_col is not None and pd.notna(row[group_node_col]):
                group_node_name = str(row[group_node_col]).strip()
                if group_node_name:
                    if group_node_name in group_nodes:
                        parent_node = group_nodes[group_node_name]
                    else:
                        group_node = schema.add_schemanode_attributes(
                            schemanode_dataypes=[],
                            schemanode_ontologies=[],
                            parent_schemanode=root_node,
                            schemanode_id=group_node_name,
                            schemanode_name=group_node_name,
                            schemanode_desc=f"Grouping of {group_node_name}",
                            schemanode_type=SchemaNodeType.GROUP,
                            corresponding_column_name=group_node_name,
                        )
                        group_nodes[group_node_name] = group_node
                        parent_node = group_node

            # create the schemanode
            schemanodes: List[SchemaNode] = []
            schemanode_name = ontology_name
            if schemanode_name_col is not None and pd.notna(row[schemanode_name_col]):
                parsed_schemanode_name = str(row[schemanode_name_col]).strip()
                if parsed_schemanode_name:
                    schemanode_name = parsed_schemanode_name

            schemanode_desc = f"Schema node for {ontology_node.ontology_name} with datatype {datatype_node.name}"
            if schemanode_description_col is not None and pd.notna(row[schemanode_description_col]):
                parsed_schemanode_desc = str(row[schemanode_description_col]).strip()
                if parsed_schemanode_desc:
                    schemanode_desc = parsed_schemanode_desc

            schemanode_type = SchemaNodeType.ATOMIC_ATTRIBUTE
            if schemanode_type_col is not None and pd.notna(row[schemanode_type_col]):
                parsed_schemanode_type = str(row[schemanode_type_col]).strip()
                if parsed_schemanode_type:
                    schemanode_type = SchemaNodeType[parsed_schemanode_type]
            # per default we assume no timeseries, as this makes it possible to have more simple
            # schema csv files without any time series column if the data has no extra time dimenstion
            if is_timeseries_colname not in row or row[is_timeseries_colname] is None or pd.isna(row[is_timeseries_colname]):
                corresponding_is_timeseries = False
            else:
                corresponding_is_timeseries = self.__parse_bool_like(str(row[is_timeseries_colname]).strip())

            schemanode_id = f"{ontology_node.ontology_name}:{datatype_node.datatype_id}"
            if schemanode_id in schemanode_ids:
                print(schemanode_ids)
                raise ValueError(f"Row {idx}: Schema node with ID {schemanode_id} already exists. Please check ontology/datatype duplicates.")
            colname = schemanode_name
            node_name = schemanode_name
            schemanode = schema.add_schemanode_attributes(
                schemanode_dataypes=[datatype_node],
                schemanode_ontologies=[ontology_node],
                parent_schemanode=parent_node,
                schemanode_id=schemanode_id,
                schemanode_name=node_name,
                schemanode_type=schemanode_type,
                schemanode_desc=schemanode_desc,
                corresponding_column_name=colname,
                is_timeseries=corresponding_is_timeseries,
            )
            schemanodes.append(schemanode)
            schemanode_ids.add(schemanode_id)
        return schema

    def __parse_bool_like(self, raw: Any) -> bool:
        """
        Parse permissive boolean-like values. Accepts 'y','yes','true','1' => True;
        'n','no','false','0' => False. Case-insensitive. Raises ValueError for others.
        """
        if raw is None:
            raise ValueError("is_timeseries value cannot be None")
        s = str(raw).strip().lower()
        if s in ("y", "yes", "true", "1"):
            return True
        if s in ("n", "no", "false", "0"):
            return False
        raise ValueError(f"Invalid boolean-like value for is_timeseries: '{raw}'")

    def __generate_timeseries_data_for_group(self,
                                             schema: Schema,
                                             group_node: SchemaNode,
                                             entries_to_generate: int,
                                             missing_ratio: float,
                                             base_date: datetime,
                                             minutes_between: int,
                                             patient_id: str,
                                             timestamp_formats: List[str]) -> List[List[DataEntry]]:
        """
        Generate timeseries data for a single group node.
        All timeseries attributes in the group share the same timestamps and appear together in each row.

        Note: This function assumes all children of group_node are timeseries attributes.
        The caller must validate this before calling.

        Args:
            schema: The schema to use for data generation
            group_node: The group node being processed (all children must be timeseries)
            entries_to_generate: Number of entries per timeseries
            missing_ratio: Ratio of missing values
            base_date: Base datetime for timestamp generation
            minutes_between: Minutes between consecutive timestamps
            patient_id: ID of the patient
            timestamp_formats: List of timestamp format strings to randomly select from

        Returns:
            List of rows, where each row is a list of DataEntry objects
        """
        entries_for_group: List[List[DataEntry]] = []

        # Get timeseries attributes from group node's children (leaf nodes only)
        timeseries_attrs = [child for child in group_node.children_nodes if len(child.children_nodes) == 0]

        # Generate values for ALL timeseries attributes in this group (per schemanode, not per datatype)
        attr_values: Dict[SchemaNode, List[Any]] = {}
        for ts_attr in timeseries_attrs:
            values = schema.generate_data_for_schemanode(
                schemanode=ts_attr,
                num_values=entries_to_generate,
                missing_ratio=missing_ratio)

            if not values:
                raise RuntimeError(f"Schema generation returned no values for attribute {ts_attr.schemanode_name}")
            if len(values) != entries_to_generate:
                raise RuntimeError(f"Schema generation returned {len(values)} values instead of the requested {entries_to_generate} for attribute {ts_attr.schemanode_name}")

            attr_values[ts_attr] = values

        # Create one row per timeseries entry, with ALL attributes populated in the same row
        timestamp_format = random.choice(timestamp_formats)
            # the same format per group for all entries, but random per group
        for entry_idx in range(entries_to_generate):
            ts_row: List[DataEntry] = []
            timestamp = (base_date + timedelta(minutes=entry_idx * minutes_between)).strftime(timestamp_format)
            visit_id = f"visit_{patient_id}_0_{entry_idx}"

            # Add entries for all timeseries attributes with their respective generated values
            for ts_attr in timeseries_attrs:
                value = attr_values[ts_attr][entry_idx]
                entry = DataEntry(
                    value=value,
                    timestamp=timestamp,
                    visit_id=visit_id,
                    corresponding_schemanode=ts_attr)
                ts_row.append(entry)

            entries_for_group.append(ts_row)

        return entries_for_group

    def __generate_patient_rows_and_group_entries(self, schema: Schema, num_patients: int,
                                                  missing_ratio: float,
                                                  max_entries_timeseries: int,
                                                  timestamp_formats: Optional[List[str]] = None) -> \
                  Dict[str, Dict[SchemaNode, List[List[DataEntry]]]]:
        """
        Helper function that generates example data according to the schema.
        The return value is a nested dictionary mapping patient IDs to group nodes to a List.
        Each entry of this list represents one row of this group. Each row then contains
        multiple DataEntries!

        - Non-timeseries attributes from all non-timeseries groups get ONE row under a virtual group node
        - Each timeseries group gets INDIVIDUAL rows with only that timeseries populated
        - All other timeseries in those rows are null
        - Timeseries are kept temporally close by using a shared base_date

        Args:
            schema: The schema to use for data generation
            num_patients: Number of patients to generate data for
            missing_ratio: Ratio of missing values
            max_entries_timeseries: Maximum number of timeseries entries per attribute per patient
            timestamp_formats: List of timestamp format strings to use. If None, defaults to ['%Y-%m-%dT%H:%M:%SZ']

        Returns:
            patient_id2group_node2DataEntriesRows: Dict[str, Dict[SchemaNode, List[List[DataEntry]]]]
        """
        if num_patients <= 0:
            raise ValueError("num_patients must be > 0")

        # Set default timestamp formats if not provided
        if timestamp_formats is None:
            timestamp_formats = ['%Y-%m-%dT%H:%M:%SZ']

        # Collect all group nodes (nodes with children)
        all_group_nodes: List[SchemaNode] = [n for n in schema.schemanodes.values() if len(n.children_nodes) > 0]
        if not all_group_nodes:
            raise ValueError("Schema must contain at least one group node (a node with children) to generate patient and timeseries data.")

        # Separate timeseries and non-timeseries groups based on their is_timeseries flag
        timeseries_groups: List[SchemaNode] = []
        non_timeseries_groups: List[SchemaNode] = []

        for group_node in all_group_nodes:
            # Skip the root node if it has no is_timeseries set (intermediate node)
            if group_node.is_timeseries is None:
                continue
            elif group_node.is_timeseries:
                timeseries_groups.append(group_node)
            else:
                non_timeseries_groups.append(group_node)

        # Collect all non-timeseries attributes across all non-timeseries groups
        all_non_timeseries_attrs: List[SchemaNode] = []
        for group in non_timeseries_groups:
            all_non_timeseries_attrs.extend([child for child in group.children_nodes if len(child.children_nodes) == 0])

        # Create a virtual group node for all non-timeseries data
        virtual_non_ts_group = SchemaNode(
            schemanode_id="__virtual_non_timeseries__",
            schemanode_name="Non-Timeseries Data",
            schemanode_desc="Virtual group containing all non-timeseries attributes",
            schemanode_type=SchemaNodeType.GROUP,
            schemanode_datatype=[],
            schemanode_ontology=[],
            parent_node=None,
            children_nodes=all_non_timeseries_attrs,
            corresponding_column_name="non_timeseries",
            is_timeseries=False
        )

        # Generate data for all patients
        patient_id2group_node2DataEntriesRows: Dict[str, Dict[SchemaNode, List[List[DataEntry]]]] = {}

        for patient_idx in range(num_patients):
            pid = f"patient_{patient_idx}"
            patient_id2group_node2DataEntriesRows[pid] = {}

            # Generate non-timeseries data (one row for all non-timeseries attributes)
            if all_non_timeseries_attrs:
                non_ts_row: List[DataEntry] = []
                for attribute_node in all_non_timeseries_attrs:
                    values = schema.generate_data_for_schemanode(
                        schemanode=attribute_node,
                        num_values=1,
                        missing_ratio=missing_ratio)
                    value = values[0] if values else None
                    entry = DataEntry(
                        value=value,
                        timestamp=None,
                        visit_id=None,
                        corresponding_schemanode=attribute_node)
                    non_ts_row.append(entry)

                patient_id2group_node2DataEntriesRows[pid][virtual_non_ts_group] = [non_ts_row]

            # Generate timeseries data for all timeseries groups
            # Determine how many timeseries entries to generate for this patient
            entries_to_generate = random.randint(1, max(1, max_entries_timeseries))

            # Generate a shared time window for all timeseries to keep them close together
            base_date = datetime.now(timezone.utc) - timedelta(days=random.randint(0, 365))
            minutes_between = random.randint(1, 20)

            for timeseries_group in timeseries_groups:
                entries_for_group = self.__generate_timeseries_data_for_group(
                    schema=schema,
                    group_node=timeseries_group,
                    entries_to_generate=entries_to_generate,
                    missing_ratio=missing_ratio,
                    base_date=base_date,
                    minutes_between=minutes_between,
                    patient_id=pid,
                    timestamp_formats=timestamp_formats
                )
                patient_id2group_node2DataEntriesRows[pid][timeseries_group] = entries_for_group

        return patient_id2group_node2DataEntriesRows
