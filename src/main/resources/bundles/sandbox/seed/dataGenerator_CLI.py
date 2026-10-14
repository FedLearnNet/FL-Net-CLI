"""
A small script to generate some example csv data for testing purposes.
For usage use --help
"""
import argparse
import os
import sys

from concurrent.futures import ProcessPoolExecutor, as_completed
from datetime import datetime
from typing import Optional
from helper.FedDBDataInstance import FedDBDataInstance
from helper.cohort import get_or_create_cohort
from helper.connector import (
    get_or_create_connector,
    load_connector_json,
    run_connector,
    upload_connector_input,
)
from helper.util import clean_schema, clean_datatypes, clean_ontologies

DEFAULT_DATAMODELER_URL = "http://localhost:8086"
DEFAULT_LOCAL_LEARNING_URL = "http://localhost:8081"

# Local auth defaults
LOCAL_KEYCLOAK_SERVER_URL = "https://staging.featurecloud.ai/feddb/local-auth"
LOCAL_KEYCLOAK_REALM = "fedDB-local"
LOCAL_KEYCLOAK_CLIENT_ID = "frontend"
LOCAL_KEYCLOAK_USERNAME = "test"
LOCAL_KEYCLOAK_PASSWORD = "test"
LOCAL_KEYCLOAK_CLIENT_SECRET = None
VERIFY_SSL = True

# Defaults of a client deployment, used by setup-clients. The local learning api
# and the keycloak are both reached via the address of the client.
CLIENT_LOCAL_LEARNING_PATH = "/local-learning-api"
CLIENT_KEYCLOAK_PATH = "/auth"
CLIENT_KEYCLOAK_REALM = "FLNet-Client"


def _add_global_datamodeler_arg(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--global-datamodeler-url", "-gdm",
        type=str,
        default=DEFAULT_DATAMODELER_URL,
        help="The URL of the global datamodeler.",
    )


def _add_schema_filename_arg(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "schema_filename",
        type=str,
        help="The schema file containing info about the schema.",
    )


def _add_keycloak_args(parser: argparse.ArgumentParser, realm: str) -> None:
    parser.add_argument("--keycloak-realm", type=str, default=realm)
    parser.add_argument("--keycloak-client-id", type=str, default=LOCAL_KEYCLOAK_CLIENT_ID)
    parser.add_argument("--keycloak-username", type=str, default=LOCAL_KEYCLOAK_USERNAME)
    parser.add_argument("--keycloak-password", type=str, default=LOCAL_KEYCLOAK_PASSWORD)
    parser.add_argument("--keycloak-client-secret", type=str, default=LOCAL_KEYCLOAK_CLIENT_SECRET)
    parser.add_argument("--verify-ssl", action=argparse.BooleanOptionalAction, default=VERIFY_SSL)


def _request_auth_headers(args: argparse.Namespace, keycloak_server_url: str) -> dict:
    return {
        "keycloak_server_url": keycloak_server_url,
        "realm": args.keycloak_realm,
        "client_id": args.keycloak_client_id,
        "username": args.keycloak_username,
        "password": args.keycloak_password,
        "client_secret": args.keycloak_client_secret,
        "verify_ssl": args.verify_ssl,
        "scope": "openid",
    }


def _setup_client(args: argparse.Namespace, info: FedDBDataInstance, connector_config: dict,
                  cohort_name: str, client_url: str, data_filename: str) -> None:
    """
    Creates the cohort and the connector on one client and runs the connector
    with the given data file.
    """
    client_url = client_url.rstrip("/")
    local_learning_api = client_url + CLIENT_LOCAL_LEARNING_PATH
    request_auth_headers = _request_auth_headers(args, client_url + CLIENT_KEYCLOAK_PATH)

    cohort_id = get_or_create_cohort(
        local_learning_api_url=local_learning_api,
        name=cohort_name,
        desc="No description available",
        global_schema_id=info.get_global_schema_id(),
        request_auth_headers=request_auth_headers
    )
    print(f"Using cohort {cohort_name} with id {cohort_id}.")

    connector_id = get_or_create_connector(
        local_learning_api_url=local_learning_api,
        cohort_id=cohort_id,
        connector_config=connector_config,
        name=f"{cohort_name}_connector",
        request_auth_headers=request_auth_headers
    )
    print(f"Using connector with id {connector_id}.")

    file_id = upload_connector_input(
        local_learning_api_url=local_learning_api,
        cohort_id=cohort_id,
        connector_id=connector_id,
        data_filename=data_filename,
        input_config=connector_config.get("inputConfig"),
        request_auth_headers=request_auth_headers
    )
    print(f"Uploaded {data_filename} with file id {file_id}.")

    run = run_connector(
        local_learning_api_url=local_learning_api,
        connector_id=connector_id,
        request_auth_headers=request_auth_headers,
        delete_existing_patients=args.delete_existing_patients
    )
    print(
        f"Connector run {run.get('id')} finished: "
        f"{run.get('newEntities')} new, {run.get('updatedEntities')} updated, "
        f"{run.get('unchangedEntities')} unchanged, {run.get('failedEntities')} failed patients, "
        f"{run.get('newDataEntries')} new and {run.get('failedDataEntries')} failed data entries."
    )


def _setup_client_worker(args: argparse.Namespace, info: FedDBDataInstance, connector_config: dict,
                         cohort_name: str, client_url: str, data_filename: str) -> Optional[str]:
    """
    Runs _setup_client in a worker process. Returns the error message if the
    setup failed, otherwise None.
    """
    try:
        _setup_client(args, info, connector_config, cohort_name, client_url, data_filename)
    except (ValueError, RuntimeError, OSError) as e:
        return str(e)
    return None


def _add_generation_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--num_patients", "-n", type=int, default=1000, help="The number of patients.")
    parser.add_argument("--missing_ratio", "-m", type=float, default=0.1, help="The ratio of missing values.")
    parser.add_argument(
        "--max_entries_timeseries",
        type=int,
        default=5,
        help="Maximum number of entries per timeseries for one patient."
    )
    parser.add_argument(
        "--timestamp-formats",
        type=str,
        default="%Y-%m-%dT%H:%M:%SZ,",
        help="Comma-separated list of timestamp format strings."
    )


def main():
    parser = argparse.ArgumentParser(
        description="Data generation and datamodeler utilities."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    generate_parser = subparsers.add_parser(
        "generate-synthetic-data",
        help="Generate synthetic data from a schema file."
    )
    generate_subparsers = generate_parser.add_subparsers(dest="generate_mode", required=True)

    generate_csv_parser = generate_subparsers.add_parser(
        "csv",
        help="Generate synthetic data and write it to a csv."
    )
    _add_schema_filename_arg(generate_csv_parser)
    _add_generation_args(generate_csv_parser)
    generate_csv_parser.add_argument(
        "--output-filename", "-o",
        type=str,
        default="",
        help="The output csv file to write the data to."
    )

    generate_upload_parser = generate_subparsers.add_parser(
        "upload-local-learning",
        help="Generate synthetic data and upload it to local learning."
    )
    _add_schema_filename_arg(generate_upload_parser)
    _add_generation_args(generate_upload_parser)
    _add_global_datamodeler_arg(generate_upload_parser)
    generate_upload_parser.add_argument(
        "--local-learning-url", "-ll",
        type=str,
        default=DEFAULT_LOCAL_LEARNING_URL,
        help="The URL of the local learning service used to create a cohort."
    )
    generate_upload_parser.add_argument("--keycloak-server-url", type=str, default=LOCAL_KEYCLOAK_SERVER_URL)
    _add_keycloak_args(generate_upload_parser, LOCAL_KEYCLOAK_REALM)

    upload_parser = subparsers.add_parser(
        "upload-schema",
        help="Upload schema, datatypes and ontologies to the datamodeler."
    )
    _add_schema_filename_arg(upload_parser)
    _add_global_datamodeler_arg(upload_parser)

    setup_parser = subparsers.add_parser(
        "setup-clients",
        help="Create a cohort and a connector on each client for an already uploaded schema and run the connector."
    )
    _add_schema_filename_arg(setup_parser)
    _add_global_datamodeler_arg(setup_parser)
    setup_parser.add_argument(
        "--connector-json", "-c",
        type=str,
        required=True,
        help="The exported connector json the connector is created from."
    )
    setup_parser.add_argument(
        "--client",
        nargs=2,
        action="append",
        required=True,
        metavar=("CLIENT_URL", "DATA_FILE"),
        help="The address of a client (e.g. http://localhost:8251) and the file (csv or zip) "
             "the connector is run with on that client. Can be given multiple times."
    )
    setup_parser.add_argument(
        "--cohort-name",
        type=str,
        default="",
        help="The name of the cohort, defaults to the name of the schema file."
    )
    setup_parser.add_argument(
        "--delete-existing-patients",
        action="store_true",
        help="Delete all existing patients of the cohort before the import."
    )
    _add_keycloak_args(setup_parser, CLIENT_KEYCLOAK_REALM)

    clean_parser = subparsers.add_parser(
        "clean-datamodeler",
        help="Clean schema/datatype entries or all entries in the datamodeler."
    )
    _add_global_datamodeler_arg(clean_parser)
    clean_subparsers = clean_parser.add_subparsers(dest="clean_scope", required=True)
    clean_subparsers.add_parser("schema-datatype", help="Clean schemas and datatypes only.")
    clean_subparsers.add_parser("all", help="Clean schemas, datatypes and ontologies.")

    args = parser.parse_args()

    if args.command == "clean-datamodeler":
        datamodeler_api = args.global_datamodeler_url
        if datamodeler_api == DEFAULT_DATAMODELER_URL:
            print(f"WARNING: Cleaning datamodeler on default URL: {datamodeler_api}")
        clean_schema(datamodeler_api)
        clean_datatypes(datamodeler_api)
        if args.clean_scope == "all":
            clean_ontologies(datamodeler_api)
        print("Datamodeler cleaned.")
        return

    if args.command == "generate-synthetic-data":
        schema_filename = args.schema_filename
        info = FedDBDataInstance(schema_filename)
        timestamp_formats = [fmt.strip() for fmt in args.timestamp_formats.split(",") if fmt.strip()]

        if args.generate_mode == "csv":
            output_filename = args.output_filename
            if not output_filename:
                basename = os.path.basename(schema_filename).split(".")[0]
                output_filename = f"{basename}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv"
                output_filename = "generated_data_synthetic_" + output_filename

            df = info.create_data(
                num_patients=args.num_patients,
                missing_ratio=args.missing_ratio,
                max_entries_timeseries=args.max_entries_timeseries,
                timestamp_formats=timestamp_formats
            )
            df.to_csv(output_filename, index=False)
            print(f"Synthetic data generated and written to {output_filename}")
            print("Program finished successfully.")
            return

        if args.generate_mode == "upload-local-learning":
            datamodeler_api = args.global_datamodeler_url
            local_learning_api = args.local_learning_url

            if datamodeler_api == DEFAULT_DATAMODELER_URL:
                print(f"WARNING: Uploading schema to default URL: {datamodeler_api}")
            info.upload_schema(datamodeler_api_url=datamodeler_api)
            print("Schema, datatypes and ontologies uploaded successfully.")

            request_auth_headers = _request_auth_headers(args, args.keycloak_server_url)

            info.create_upload_cohort(
                name=os.path.basename(schema_filename).split(".")[0],
                desc="No description available",
                local_learning_api_url=local_learning_api,
                request_auth_headers=request_auth_headers
            )
            print("Cohort created and uploaded successfully.")

            info.upload_harmonized_data(
                local_learning_api_url=local_learning_api,
                request_auth_headers=request_auth_headers,
                num_patients=args.num_patients,
                missing_ratio=args.missing_ratio,
                max_entries_timeseries=args.max_entries_timeseries,
                timestamp_formats=timestamp_formats
            )
            print("Synthetic data uploaded successfully.")
            print("Program finished successfully.")
            return

        print("Program finished successfully.")
        return

    if args.command == "upload-schema":
        schema_filename = args.schema_filename
        datamodeler_api = args.global_datamodeler_url
        if datamodeler_api == DEFAULT_DATAMODELER_URL:
            print(f"WARNING: Uploading schema to default URL: {datamodeler_api}")

        info = FedDBDataInstance(schema_filename)
        info.upload_schema(datamodeler_api_url=datamodeler_api)
        print("Schema, datatypes and ontologies uploaded successfully.")
        print("Program finished successfully.")
        return

    if args.command == "setup-clients":
        schema_filename = args.schema_filename
        datamodeler_api = args.global_datamodeler_url
        for _, data_filename in args.client:
            if not os.path.isfile(data_filename):
                parser.error(f"Data file {data_filename} does not exist.")
        connector_config = load_connector_json(args.connector_json)
        cohort_name = args.cohort_name or os.path.basename(schema_filename).split(".")[0]

        # the schema is not uploaded here, use upload-schema for that
        info = FedDBDataInstance(schema_filename)
        info.use_uploaded_schema(datamodeler_api_url=datamodeler_api)

        # the clients are set up in parallel, one process per client
        failed_clients = []
        with ProcessPoolExecutor(max_workers=len(args.client)) as executor:
            futures = {}
            for client_url, data_filename in args.client:
                print(f"Setting up client {client_url} with {data_filename}...")
                future = executor.submit(_setup_client_worker, args, info, connector_config,
                                         cohort_name, client_url, data_filename)
                futures[future] = client_url
            for future in as_completed(futures):
                client_url = futures[future]
                error = future.result()
                if error is not None:
                    print(f"ERROR: Setting up client {client_url} failed: {error}")
                    failed_clients.append(client_url)
                else:
                    print(f"Client {client_url} set up.")

        if failed_clients:
            print(f"Program finished with errors for: {', '.join(failed_clients)}")
            sys.exit(1)
        print("Program finished successfully.")
        return


if __name__ == "__main__":
    main()
