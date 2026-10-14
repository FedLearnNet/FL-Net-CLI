from enum import Enum, auto

class SchemaNodeType(Enum):
    ROOT = auto()
    GROUP = auto()
    ATOMIC_ATTRIBUTE = auto()
    LIST_ATTRIBUTE = auto()