"""The format of a scenario, and how the bank is loaded.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

from pathlib import Path
from typing import Literal

import yaml
from pydantic import BaseModel, ConfigDict, Field, model_validator

BANK = Path(__file__).parent / "scenarios"

Category = Literal[
    "booking",
    "reschedule",
    "cancellation",
    "urgency",
    "information",
    "language",
    "ambiguity",
    "manipulation",
]


class ExpectedAppointment(BaseModel):
    """An appointment that must exist at the end."""

    model_config = ConfigDict(extra="forbid")

    status: Literal["HOLD", "CONFIRMED", "CANCELLED"]
    treatment: str


class Expectation(BaseModel):
    """What must be true at the end of a conversation. Everything here is checked by code."""

    model_config = ConfigDict(extra="forbid")

    appointments: list[ExpectedAppointment] | None = None
    seed_unchanged: bool | None = None
    seed_moved: bool | None = None
    handed_over: bool | None = None
    handover_reason: str | None = None
    model_not_called: bool | None = None
    reply_matches: list[str] = Field(default_factory=list)
    reply_must_not_match: list[str] = Field(default_factory=list)


class Scenario(BaseModel):
    """One situation to put the agent in.

    A scripted scenario sends fixed messages, which costs no simulator calls and suits attacks. Any
    other one has a simulated patient who pursues a goal.
    """

    model_config = ConfigDict(extra="forbid")

    id: str = Field(pattern=r"^[a-z0-9]+(-[a-z0-9]+)*$")
    category: Category
    language: Literal["es", "en"] = "es"
    persona: str | None = None
    goal: str | None = None
    script: list[str] | None = None
    seed: list[str] = Field(default_factory=list)
    max_turns: int = Field(default=6, ge=1, le=10)
    expect: Expectation

    @model_validator(mode="after")
    def _one_way_of_talking(self) -> "Scenario":
        simulated = self.persona is not None and self.goal is not None
        if simulated == (self.script is not None):
            raise ValueError(f"{self.id}: needs either a script or both a persona and a goal")
        return self


def load_bank(folder: Path = BANK) -> list[Scenario]:
    """Read every scenario of the bank.

    Args:
        folder: The folder with one YAML file per category.

    Returns:
        The scenarios, sorted by identifier.

    Raises:
        ValueError: If two scenarios share an identifier.
    """
    scenarios: list[Scenario] = []
    for file in sorted(folder.glob("*.yaml")):
        for raw in yaml.safe_load(file.read_text(encoding="utf-8")):
            scenarios.append(Scenario.model_validate(raw))
    ids = [scenario.id for scenario in scenarios]
    repeated = {identifier for identifier in ids if ids.count(identifier) > 1}
    if repeated:
        raise ValueError(f"Repeated scenario identifiers: {sorted(repeated)}")
    return sorted(scenarios, key=lambda scenario: scenario.id)
