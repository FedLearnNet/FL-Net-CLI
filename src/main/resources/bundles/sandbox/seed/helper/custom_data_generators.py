"""
Custom data generation functions for DatatypeNode.

Each function should have the signature:
    function_name(n: int, missing_ratio: float, null_values: List[Any]) -> List[Any]
Each function should return a list of length n, with values generated containing
missing_ratio * n missing values randomly chosen from the given null_values list.

These functions override the _generate_with_limits method of DatatypeNode.
"""
import random
from typing import List, Any

def generate_blood_pressure_string(n: int, missing_ratio: float, null_values: List[Any]) -> List[Any]:
    """
    Generates blood pressure values as strings in the format "systolic/diastolic"
    e.g., "120/80", "135/85", etc.

    Systolic pressure range: 90-180 mmHg (typical clinical range)
    Diastolic pressure range: 60-120 mmHg (typical clinical range)

    Args:
        n: Number of blood pressure values to generate
        missing_ratio: Ratio of missing values (0.0 to 1.0)
        null_values: List of possible null/missing values to use

    Returns:
        List of blood pressure strings or null values
    """
    result = []
    for _ in range(n):
        if random.random() < missing_ratio:
            # Generate a missing value
            if null_values:
                result.append(random.choice(null_values))
            else:
                result.append(None)
        else:
            # Generate realistic blood pressure values
            systolic = random.randint(90, 180)
            diastolic = random.randint(60, 120)
            # Ensure diastolic is always less than systolic (medical constraint)
            if diastolic >= systolic:
                diastolic = systolic - random.randint(10, 40)
            result.append(f"{systolic}/{diastolic}")

    return result
