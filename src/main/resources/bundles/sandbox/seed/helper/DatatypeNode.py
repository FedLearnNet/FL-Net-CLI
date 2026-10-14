import random
import datetime
import json
from typing import Dict, List, Any, Union, Tuple, Callable, Optional, TYPE_CHECKING
from enum import Enum
import string
import requests

if TYPE_CHECKING:
    from .Schema import Schema

class DataTypeEnum(Enum):
    BOOL = "BOOLEAN"
    STRING = "STRING"
    INT = "INT"
    FLOAT = "FLOAT"
    DATE = "DATE"
    DATE_TIME = "DATE_TIME"
    CATEGORICAL = "CATEGORICAL"

class DatatypeNode:
    """
    Describes one datatype, the formatting information of data.
    Also allows for different validations to be saved in this class.
    Can be used to generate and validate data for this datatype.
    Methods:
        __init__: See the __init__ documentation
        generate_data(self, n, missing_ratio):
            Generates n datapoints of this datatype with the given missing_ratio.
            The default method just picks randomly from the allowed values and
            uses the null_values list for missing values.
            If no allowed values are given, it picks randomly depending on the
            type:
            for some datatypes like text with no allowed_values the method
            cannot savely generate data so it will throw an ValueError if
            called.
            For integers, the default limits are -2147483648 and 2147483647
            For floats, the default limits are 0 and 1
            For dates/timestamps, the default limits are 1900-01-01 and 2100-01-01
        validate(self, datapoint): Validates the data against the allowed values
            The default method just checks the the given datapoint is inside
            the allowed values/inside the limits given by the allowed values
            it calls the validate_custom(self, datapoint) method.
            That method must be overwritten for custom validations.
    """
    datatype_id: str # an id for the datatype.
                     # Only used in this code and not at the data modeler server
                     # The datamodeler server uses the db_datatype_id for that
    datatype: DataTypeEnum
        # The type of the data, must be one of:
        # bool, text, int, float, date, date-time, categorical
    allowed_values:List[Any]
        # The allowed values for the data.
        # For numerical data and date/datetime, two values are interpreted as
        # lower and upper inclusive limits.
        # the bigger value is taken as the upper limit
        # the lower and upper limit are inclusive
        # if no limits are given, for integers a value in the in32 range is choosen
        # for float a value between 0 and 1 is choosen
        # for dates, the default min and max values are 1900-01-01 and 2100-01-01
    null_values:List[Any]
        # a list containing all values that should be interpreted as no data
        # for this datatype
    name:str
        # The name of the datatype, Optional
    desc:str
        # A description of the datatype, Optional
    db_datatype_id: Union[str, None] # the id for the datatype in the database
    custom_generation_function: Optional[Callable] # optional custom function to generate data
    schema: Optional["Schema"]
    validations: List[Dict[str, str]]
        # Structured validations to send to the backend.
        # Each entry is a dict with keys: 'name' (DataTypeValidationType), 'validator', 'message'.
        # Used when the allowed_values column contains validation metadata.


    def __init__(self,
                 datatype_id:str,
                 datatype: DataTypeEnum,
                 allowed_values: Optional[List[Any]] = None,
                 null_values:Union[List[Any], None]=None,
                 name:str="",
                 desc:str="",
                 db_datatype_id: Union[str, None] = None,
                 custom_generation_function: Optional[Callable] = None,
                 schema: Optional["Schema"] = None,
                 validations: Optional[List[Dict[str, str]]] = None,
                 raw_validation_string: Optional[str] = None) -> None:
        if datatype_id is None:
            raise ValueError("Datatype id must be given")
        self.datatype_id = datatype_id

        # type validations
        if not isinstance(datatype, DataTypeEnum):
            raise ValueError(f"Invalid type {datatype}. Must be a DataType enum value.")
        self.datatype = datatype
        if allowed_values is None:
            allowed_values = []
        if raw_validation_string is not None and (allowed_values or validations):
            raise ValueError(
                "Pass either raw_validation_string or allowed_values/validations, not both."
            )
        self.allowed_values = self._normalize_allowed_values(allowed_values)
        if null_values is None:
            null_values = [None]
        self.null_values = null_values

        # name and description are simply taken as is, they are anyways used more
        # for documentation purposes
        self.name = name
        self.desc = desc
        self.db_datatype_id = db_datatype_id
        self.custom_generation_function = custom_generation_function
        self.schema = schema
        self.validations = validations if validations is not None else []

        if raw_validation_string is not None:
            parsed_allowed_values, parsed_validations = self._parse_raw_validation_string(raw_validation_string)
            self.allowed_values = parsed_allowed_values
            self.validations = parsed_validations

        if len(self.allowed_values) == 1:
            # this case doesnt make any sense, so we raise an error
            raise ValueError(f"Only one allowed value {self.allowed_values} for datatype {self.datatype}" +\
                             " Either have no allowed values, two or more")

        # Normalize: 2-value allowed_values for numeric/date types → MIN/MAX validations
        if not self.validations and self.datatype in [
            DataTypeEnum.INT, DataTypeEnum.FLOAT, DataTypeEnum.DATE, DataTypeEnum.DATE_TIME
        ] and len(self.allowed_values) == 2:
            min_val = self._apply_func_to_value_list(self.allowed_values, self.datatype.value, min)
            max_val = self._apply_func_to_value_list(self.allowed_values, self.datatype.value, max)
            self.validations = [
                {"name": "MIN", "validator": str(min_val), "message": f"Value must be greater or equal to {min_val}"},
                {"name": "MAX", "validator": str(max_val), "message": f"Value must be smaller or equal to {max_val}"},
            ]

    def _normalize_allowed_values(self, allowed_values: List[Any]) -> List[Any]:
        normalized_allowed_values: List[Any] = []
        for value in allowed_values:
            if value is None or str(value).lower() in [""]:
                normalized_allowed_values.append(None)
                continue

            if self.datatype in [DataTypeEnum.STRING, DataTypeEnum.CATEGORICAL]:
                normalized_allowed_values.append(str(value))
            elif self.datatype == DataTypeEnum.DATE:
                normalized_allowed_values.append(self._read_in_date(value).date().isoformat())
            elif self.datatype == DataTypeEnum.DATE_TIME:
                normalized_allowed_values.append(self._parse_iso_to_utc(str(value)).isoformat())
            elif self.datatype == DataTypeEnum.INT:
                normalized_allowed_values.append(int(value))
            elif self.datatype == DataTypeEnum.FLOAT:
                normalized_allowed_values.append(float(value))
            elif self.datatype == DataTypeEnum.BOOL:
                lowered_value = str(value).strip().lower()
                if lowered_value == 'true':
                    normalized_allowed_values.append(True)
                elif lowered_value == 'false':
                    normalized_allowed_values.append(False)
                else:
                    raise ValueError(f"Invalid boolean value: {value}")
            else:
                normalized_allowed_values.append(value)
        return normalized_allowed_values

    def _parse_raw_validation_string(self, raw_validation_string: str) -> Tuple[List[Any], List[Dict[str, str]]]:
        """
        Parse a raw JSON string containing allowed values, validations, or both.
        Supported formats:
        - simple comma separated list
        - JSON array: allowed values only
        - JSON object with validation names: validations only
        - JSON object with keys 'allowed_values' and/or 'validations'
        """
        cleaned_validation_string = str(raw_validation_string).strip().strip('"').strip("'")

        try:
            parsed_raw_value = json.loads(cleaned_validation_string)
        except json.JSONDecodeError as exp:
            # could be the simple comma-separated format, we try to parse it as such
            return self._normalize_allowed_values([v.strip() for v in cleaned_validation_string.split(",") if v.strip()]), []

        if isinstance(parsed_raw_value, list):
            return self._normalize_allowed_values(parsed_raw_value), []

        if not isinstance(parsed_raw_value, dict):
            raise ValueError(
                "raw_validation_string must decode to a JSON array or object"
            )

        if self._looks_like_validation_map(parsed_raw_value):
            return [], self._parse_validation_map(parsed_raw_value)

        allowed_values = parsed_raw_value.get("allowed_values", [])
        validations = parsed_raw_value.get("validations", {})
        unknown_keys = set(parsed_raw_value.keys()) - {"allowed_values", "validations"}
        if unknown_keys:
            raise ValueError(
                f"Unknown keys in raw_validation_string: {sorted(unknown_keys)}. "
                "Allowed keys: ['allowed_values', 'validations']"
            )
        if not isinstance(allowed_values, list):
            raise ValueError("raw_validation_string.allowed_values must be a JSON array")
        if not isinstance(validations, dict):
            raise ValueError("raw_validation_string.validations must be a JSON object")
        return self._normalize_allowed_values(allowed_values), self._parse_validation_map(validations)

    def _looks_like_validation_map(self, candidate: Any) -> bool:
        """
        Detect if a dict represents structured datatype validations.
        """
        if not isinstance(candidate, dict) or not candidate:
            return False
        valid_names = {"REQUIRED", "MINLENGTH", "MAXLENGTH", "PATTERN", "MIN", "MAX"}
        return all(str(key).upper() in valid_names for key in candidate.keys())

    def _parse_validation_map(self, validation_map: Dict[Any, Any]) -> List[Dict[str, str]]:
        """
        Normalize a validation map into backend-ready structured validations.
        Supports values as plain validators or dicts with validator/message.
        """
        valid_names = {"REQUIRED", "MINLENGTH", "MAXLENGTH", "PATTERN", "MIN", "MAX"}
        parsed_validations: List[Dict[str, str]] = []
        for v_name, v_value in validation_map.items():
            upper_name = str(v_name).upper()
            if upper_name not in valid_names:
                raise ValueError(
                    f"Unknown validation type '{v_name}'. Allowed types: {sorted(valid_names)}"
                )

            if isinstance(v_value, str):
                value_str = v_value.strip()
                if value_str.startswith("{") and value_str.endswith("}"):
                    try:
                        v_value = json.loads(value_str)
                    except json.JSONDecodeError as exp:
                        raise ValueError(
                            f"Validation '{v_name}' value is not valid JSON: {v_value!r}"
                        ) from exp

            if isinstance(v_value, dict):
                validator = str(v_value.get("validator", ""))
                message = str(v_value.get("message", f"Validation {upper_name} failed"))
            else:
                validator = str(v_value)
                message = f"Validation {upper_name} failed"

            parsed_validations.append({
                "name": upper_name,
                "validator": validator,
                "message": message,
            })
        return parsed_validations


    def generate_data(self, n:int, missing_ratio:float=0.1) -> Union[List[Any], None]:
        """
        Generates n datapoints of this datatype with a given missing ratio
        if self.null_values is empty and missing_ratio is not 0, the method
        will throw an error, as it cannot generate missing values.

        Must be overwritten.
        As this base method returns None by checking for None results of
        generating it can be checked if the method was overwritten
        """
        # If a custom generation function is provided, use it instead
        if self.custom_generation_function is not None:
            return self.custom_generation_function(n, missing_ratio, self.null_values)

        # depending on the dataype we generate in different ways
        if self.datatype == DataTypeEnum.BOOL:
            return [random.choice([True, False]) if random.random() > missing_ratio else self._generate_missing() for _ in range(n)]

        # string and categorical data, we only need to catch if we don't have allowed values
        elif self.datatype == DataTypeEnum.STRING or self.datatype == DataTypeEnum.CATEGORICAL:
            if not self.allowed_values:
                # we just generate a random string (1 to 100 chars)
                return [''.join(random.choices(string.ascii_letters + string.digits, k=random.randint(1, 100))) for _ in range(n)]
            return [random.choice(self.allowed_values) if random.random() > missing_ratio else self._generate_missing() for _ in range(n)]

        # int, float, date, datetime behave basically the same, we need to generate inside the limits
        # if necessary though
        elif self.datatype in [DataTypeEnum.INT, DataTypeEnum.FLOAT, DataTypeEnum.DATE, DataTypeEnum.DATE_TIME]:
            # If structured validations are present, derive MIN/MAX limits from them
            if self.validations:
                min_entry = next((v for v in self.validations if v["name"] == "MIN"), None)
                max_entry = next((v for v in self.validations if v["name"] == "MAX"), None)
                if min_entry is not None and max_entry is not None:
                    raw_limits = [min_entry["validator"], max_entry["validator"]]
                    typed_limits = self.__fix_validation_limits(raw_limits)
                    return self._generate_with_limits(n, typed_limits[0], typed_limits[1], missing_ratio)
                elif min_entry is not None or max_entry is not None:
                    # only one bound — fall through to defaults
                    lower_limit, upper_limit = self._default_limits()
                    return self._generate_with_limits(n, lower_limit, upper_limit, missing_ratio)
                # No MIN/MAX validations — fall through to default limits
                lower_limit, upper_limit = self._default_limits()
                return self._generate_with_limits(n, lower_limit, upper_limit, missing_ratio)

            # either we have
            # - no allowed values (default limits)
            # - two allowed values (lower and upper limit)
            # - more than two allowed values, then we basically have categorical data
            #  and we can generate it like categorical data. We might also
            # just have one value, then we throw an error, that doesnt make any
            # sense
            if len(self.allowed_values) == 0:
                lower_limit, upper_limit = self._default_limits()
                return self._generate_with_limits(n, lower_limit, upper_limit,
                                                  missing_ratio)
            elif len(self.allowed_values) == 2:
                if self.datatype == DataTypeEnum.INT:
                    try:
                        lower_limit = min(list(map(int, self.allowed_values)))
                        upper_limit = max(list(map(int, self.allowed_values)))
                    except Exception as e:
                        raise ValueError(f"Invalid allowed values {self.allowed_values}") from e
                    return self._generate_with_limits(n, lower_limit, upper_limit,
                                                  missing_ratio)
                elif self.datatype == DataTypeEnum.FLOAT:
                    try:
                        lower_limit = min(list(map(float, self.allowed_values)))
                        upper_limit = max(list(map(float, self.allowed_values)))
                    except Exception as e:
                        raise ValueError(f"Invalid allowed values {self.allowed_values}") from e
                    return self._generate_with_limits(n, lower_limit, upper_limit,
                                                      missing_ratio)
                elif self.datatype == DataTypeEnum.DATE:
                    try:
                        lower_limit = min(list(map(self._read_in_date, self.allowed_values)))
                        upper_limit = max(list(map(self._read_in_date, self.allowed_values)))
                    except Exception as e:
                        raise ValueError(f"Invalid allowed values {self.allowed_values}") from e
                    return self._generate_with_limits(n, lower_limit, upper_limit,
                                                      missing_ratio)
                elif self.datatype == DataTypeEnum.DATE_TIME:
                    try:
                        lower_limit = min(list(map(self._parse_iso_to_utc, self.allowed_values)))
                        upper_limit = max(list(map(self._parse_iso_to_utc, self.allowed_values)))
                    except Exception as e:
                        raise ValueError(f"Invalid allowed values {self.allowed_values}") from e
                    return self._generate_with_limits(n, lower_limit, upper_limit,
                                                      missing_ratio)
            elif len(self.allowed_values) > 2:
                return [random.choice(self.allowed_values) if random.random() > missing_ratio else self._generate_missing() for _ in range(n)]
            elif len(self.allowed_values) == 1:
                raise ValueError(f"Only one allowed value {self.allowed_values} for datatype {self.datatype}")

        raise ValueError(f"Datatype {self.datatype} not implemented")

    def __fix_validation_limits(self, raw_limits: List[str]) -> List[Any]:
        """
        Convert a pair of raw string limit values (from MIN/MAX validations) to the
        correct Python types for this datatype, suitable for passing to _generate_with_limits.
        """
        if self.datatype == DataTypeEnum.INT:
            return [int(v) for v in raw_limits]
        elif self.datatype == DataTypeEnum.FLOAT:
            return [float(v) for v in raw_limits]
        elif self.datatype == DataTypeEnum.DATE:
            return [self._read_in_date(v) for v in raw_limits]
        elif self.datatype == DataTypeEnum.DATE_TIME:
            return [self._parse_iso_to_utc(v) for v in raw_limits]
        raise ValueError(f"Datatype {self.datatype} does not support MIN/MAX validations")

    def _generate_with_limits(self,
                            n:int,
                            lower_limit:Union[int, float, datetime.datetime],
                            upper_limit:Union[int, float, datetime.datetime],
                            missing_ratio: float) -> List[Any]:
        """
        Generates n datapoints of this datatype with the given limits and
        the given missing ratio
        """
        if not (type(lower_limit) is type(upper_limit)):
            raise ValueError(f"Invalid limits {lower_limit} to {upper_limit}")
        try:
            if lower_limit > upper_limit: # type: ignore
                # we checked for the same type the line before, pylint doesn't see that
                raise ValueError(f"Invalid limits {lower_limit} to {upper_limit}")
        except Exception as e:
            raise ValueError(f"Invalid limits {lower_limit} to {upper_limit}") from e

        if self.datatype == DataTypeEnum.INT:
            if not isinstance(lower_limit, int) or not isinstance(upper_limit, int):
                raise ValueError(f"Invalid limits {lower_limit} {upper_limit}")
            return [random.randint(lower_limit, upper_limit) if random.random() > missing_ratio else self._generate_missing() for _ in range(n)]
        elif self.datatype == DataTypeEnum.FLOAT:
            if isinstance(lower_limit, int):
                lower_limit = float(lower_limit)
            if isinstance(upper_limit, int):
                upper_limit = float(upper_limit)
            if not isinstance(lower_limit, float) or not isinstance(upper_limit, float):
                raise ValueError(f"Invalid limits {lower_limit} {upper_limit}")
            return [random.uniform(lower_limit, upper_limit) if random.random() > missing_ratio else self._generate_missing() for _ in range(n)]
        elif self.datatype == DataTypeEnum.DATE:
            if not isinstance(lower_limit, datetime.datetime) or not isinstance(upper_limit, datetime.datetime):
                raise ValueError(f"Invalid limits {lower_limit} {upper_limit}")
            return self._generate_random_dates(lower_limit, upper_limit, n, missing_ratio)
        elif self.datatype == DataTypeEnum.DATE_TIME:
            if not isinstance(lower_limit, datetime.datetime) or not isinstance(upper_limit, datetime.datetime):
                raise ValueError(f"Invalid limits {lower_limit} {upper_limit}")
            return self._generate_random_timestamps(lower_limit, upper_limit, n, missing_ratio)

        raise ValueError(f"Datatype {self.datatype} not implemented")

    def _generate_random_dates(self,
                            start_date: datetime.datetime,
                            end_date: datetime.datetime,
                            n: int,
                            missing_ratio: float) -> List[str]:
        """
        Generates n random dates between start_date and end_date
        Returns them as strings in iso format
        """
        # Calculate the total time range in seconds
        time_range = end_date - start_date
        total_seconds = time_range.total_seconds()

        # Generate n random timestamps
        random_dates = []
        for _ in range(n):
            if random.random() < missing_ratio:
                random_dates.append(self._generate_missing())
                continue
            random_seconds = random.uniform(0, total_seconds)
            random_date = start_date + datetime.timedelta(seconds=random_seconds)
            random_dates.append(random_date.date().isoformat())

        return random_dates

    def _generate_random_timestamps(self,
                                start_date: datetime.datetime,
                                end_date: datetime.datetime,
                                n: int,
                                missing_ratio:float) -> List[str]:
        """
        Generates n random timestamps between start_date and end_date
        Returns them as strings in iso format
        """
        # Ensure start_date and end_date are timezone-aware in UTC
        if start_date.tzinfo is None:
            start_date = start_date.replace(tzinfo=datetime.timezone.utc)
        else:
            start_date = start_date.astimezone(datetime.timezone.utc)

        if end_date.tzinfo is None:
            end_date = end_date.replace(tzinfo=datetime.timezone.utc)
        else:
            end_date = end_date.astimezone(datetime.timezone.utc)

        # Calculate the total time range in seconds
        time_range = end_date - start_date
        total_seconds = time_range.total_seconds()

        # Generate n random timestamps
        random_timestamps = []
        for _ in range(n):
            if random.random() < missing_ratio:
                random_timestamps.append(self._generate_missing())
                continue
            random_seconds = random.uniform(0, total_seconds)
            random_timestamp = start_date + datetime.timedelta(seconds=random_seconds)
            random_timestamps.append(random_timestamp.strftime('%Y-%m-%dT%H:%M:%SZ'))

        return random_timestamps

    def _default_limits(self) -> Tuple[Union[int, float, datetime.datetime],
                                       Union[int, float, datetime.datetime]]:
        """
        Returns the default limits for this datatype
        """
        if self.datatype == DataTypeEnum.INT:
            return  -2147483648, 2147483647
        elif self.datatype == DataTypeEnum.FLOAT:
            return 0, 1
        elif self.datatype == DataTypeEnum.DATE:
            return datetime.datetime(1900, 1, 1, tzinfo=datetime.timezone.utc), datetime.datetime(2100, 1, 1, tzinfo=datetime.timezone.utc)
        elif self.datatype == DataTypeEnum.DATE_TIME:
            return datetime.datetime(1900, 1, 1, 0, 0, 0, tzinfo=datetime.timezone.utc), datetime.datetime(2100, 1, 1, 0, 0, 0, tzinfo=datetime.timezone.utc)
        else:
            raise ValueError(f"Datatype {self.datatype} does not have default limits")

    def _generate_missing(self) -> Any:
        """
        Generates a missing value for this datatype
        """
        if len(self.null_values) == 0:
            raise ValueError("Asked to generate missing value, but no null values are given")
        return random.choice(self.null_values)

    def validate(self, data_point:Any) -> bool:
        """
        Validates the data against the allowed values and the validations
        """
        # The validation depends on the datatype
        if self.datatype == DataTypeEnum.BOOL:
            if data_point not in [True, False] and data_point not in self.null_values:
                return False
            return self.validate_custom(data_point)
        elif self.datatype == DataTypeEnum.CATEGORICAL:
            if data_point not in self.allowed_values and data_point not in self.null_values:
                return False
            return self.validate_custom(data_point)
        # we cannot validate string data
        elif self.datatype == DataTypeEnum.STRING:
            return self.validate_custom(data_point)

        elif self.datatype in [DataTypeEnum.INT, DataTypeEnum.FLOAT, DataTypeEnum.DATE, DataTypeEnum.DATE_TIME]:
            # either we have
            # - no allowed values (just accept if the type fits)
            # - two allowed values (lower and upper limit)
            # - more than two allowed values, then we basically have categorical data
            #  and we can validate similar to categorical data. We might also
            # just have one value, then we throw an error, that doesnt make any
            # sense

            # NO limits, just check if the type fits
            if len(self.allowed_values) == 0:
                if self.datatype == DataTypeEnum.INT:
                    if not isinstance(data_point, int):
                        return False
                    return self.validate_custom(data_point)
                elif self.datatype == DataTypeEnum.FLOAT:
                    if not isinstance(data_point, float):
                        return False
                    return self.validate_custom(data_point)
                elif self.datatype == DataTypeEnum.DATE:
                    if not self._validate_date(data_point):
                        return False
                    return self.validate_custom(data_point)

            # limits, so get the limits and check if the data point is inside
            elif len(self.allowed_values) == 2:
                try:
                    lower_limit = min(self.allowed_values)
                    upper_limit = max(self.allowed_values)
                except Exception as e:
                    raise ValueError(f"Invalid allowed values {self.allowed_values}") from e
                # int, float, date, datetime all support comparison operators,
                # the iso date string also supports this
                if self.datatype == DataTypeEnum.DATE or self.datatype == DataTypeEnum.DATE_TIME:
                    # we need to read the datapoint into a datetime object
                    data_point = self._read_in_date(data_point)

                if data_point < lower_limit or data_point > upper_limit:
                    return False
                return self.validate_custom(data_point)

            # no limits, simply some allowed values
            elif len(self.allowed_values) > 2:
                if data_point not in self.allowed_values and data_point not in self.null_values:
                    return False
                return self.validate_custom(data_point)
            # this case doesnt make any sense, so we raise an error
            elif len(self.allowed_values) == 1:
                raise ValueError(f"Only one allowed value {self.allowed_values} for datatype {self.datatype}")

        raise ValueError(f"Datatype {self.datatype} not implemented")

    def set_schema(self, schema: "Schema") -> None:
        """
        Set the parent schema reference for this node.
        """
        self.schema = schema

    def upload(self) -> None:
        """
        Upload this datatype node to the configured datamodeler.
        """
        if self.db_datatype_id:
            return
        if self.schema is None:
            raise ValueError("Datatype has no associated schema. Cannot upload.")
        if self.schema.global_server_url is None:
            raise ValueError("Global server URL is not set, use set_global_server_url() before uploading anything.")

        url = self.schema.global_server_url + "/datatype/"
        body_dict: Dict[str, Any] = {
            "description": self.desc,
            "name": self.name,
            "type": self.datatype.value,
        }

        # Validations and options are orthogonal — handle them independently.
        # self.validations is always pre-populated (including MIN/MAX from 2-value options).
        if self.validations:
            body_dict["validations"] = [
                {
                    "name": v["name"],
                    "validator": str(v["validator"]),
                    "message": v.get("message", f"Validation {v['name']} failed"),
                }
                for v in self.validations
            ]

        if self.datatype == DataTypeEnum.BOOL:
            if len(self.allowed_values) == 0:
                body_dict["options"] = ["True", "False"]
            elif len(self.allowed_values) == 2:
                if True not in self.allowed_values or False not in self.allowed_values:
                    raise ValueError(f"Boolean datatype {self.name} has wrong allowed values")
                body_dict["options"] = ["True", "False"]
            else:
                raise ValueError(f"Boolean datatype {self.name} has wrong allowed values")
        elif self.datatype in [DataTypeEnum.STRING, DataTypeEnum.CATEGORICAL]:
            body_dict["options"] = list(self.allowed_values)
        elif self.datatype in [DataTypeEnum.INT, DataTypeEnum.FLOAT, DataTypeEnum.DATE, DataTypeEnum.DATE_TIME]:
            if len(self.allowed_values) > 2:
                body_dict["options"] = list(self.allowed_values)
            # 2-value case: already normalized to self.validations in __init__
            # 0-value case: no options needed
        else:
            raise ValueError(f"Datatype {self.name} has an unknown datatype {self.datatype}")

        body_dict.setdefault("options", [])

        body_dict["options"] = [str(val) for val in body_dict["options"]]
        response = requests.post(url, json=body_dict, timeout=60)
        if response.status_code in [201, 409]:
            response_dict = response.json()
            if "id" not in response_dict:
                raise ValueError(
                    f"Failed to upload datatype {self.name}: {response.text}, could not find id in response"
                )
            self.db_datatype_id = response_dict["id"]
            return

        raise ValueError(f"Failed to upload datatype {self.name}: {response.text}")

    def _apply_func_to_value_list(self, value_list: List[Any], datatype: str, func: Callable) -> Any:
        """
        Apply min/max helper over values depending on datatype.
        """
        if datatype not in ["INT", "FLOAT", "DATE", "DATE_TIME"]:
            raise ValueError(f"Datatype {datatype} not supported for min value")
        if datatype in ["INT", "FLOAT"]:
            min_val = func([float(val) for val in value_list])
            if datatype == "INT":
                return int(min_val)
            return min_val
        if datatype in ["DATE", "DATE_TIME"]:
            for val in value_list:
                try:
                    datetime.datetime.fromisoformat(str(val))
                except ValueError as exp:
                    raise ValueError(f"Date {val} not in iso format") from exp
            return func(value_list)

    def validate_custom(self, data_point:Any) -> bool:
        """
        Validates the data against custom validations
        """
        if data_point:
            pass
        return True # if not overwritten this method should not do anything

    def _validate_date(self, datapoint: Any) -> bool:
        """
        validates a date/datetime object
        That can be given as a datetime instance or a string
        We try different formats and if one works we return True
        """
        if isinstance(datapoint, datetime.datetime):
            return True
        if not isinstance(datapoint, str):
            return False
        for date_format in ["%Y-%m-%d", "%Y-%m-%d %H:%M:%S"]: # for now only these formats
            try:
                datetime.datetime.strptime(datapoint, date_format)
                return True
            except ValueError:
                pass
        return False

    def _read_in_date(self, input_date:Any) -> datetime.datetime:
        """
        Reads in a date/datetime object
        That can be given as a datetime instance or a string
        We try different formats and if one works we return the datetime object
        """
        if isinstance(input_date, datetime.datetime):
            return input_date
        if not isinstance(input_date, str):
            raise ValueError(f"Invalid date {input_date}")
        for date_format in ["%Y-%m-%d", "%Y-%m-%d %H:%M:%S"]:
            try:
                return datetime.datetime.strptime(input_date, date_format)
            except ValueError:
                pass
        raise ValueError(f"Invalid date {input_date}")

    def _parse_iso_to_utc(self, s: str) -> datetime.datetime:
        dt = datetime.datetime.fromisoformat(s)
        if dt.tzinfo is None:
            return dt.replace(tzinfo=datetime.timezone.utc)
        return dt.astimezone(datetime.timezone.utc)
