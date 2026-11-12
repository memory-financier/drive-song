from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    """JSON은 camelCase (설계 §9)."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)
