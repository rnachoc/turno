"""What a run leaves behind, and the check that makes it safe to publish.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import re
from collections.abc import Iterator
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict

RECORDINGS = Path(__file__).parent / "recordings"

_EMAIL = re.compile(r"[\w.+-]+@[\w-]+(\.[\w-]+)+")
_SYSTEM_PATH = re.compile(
    r"(?<![a-zA-Z])[a-zA-Z]:\\\\?[a-zA-Z][^\s\"]*|(?<![\w/.])/(home|users|root)/[^\s\"]*", re.I
)
_TOKEN = re.compile(r"sk-ant-[\w-]+|local-only-[\w-]+", re.I)


class UnsafeRecordingError(Exception):
    """A recording holds something that must not be published. The message never repeats it."""


class Turn(BaseModel):
    """One message of the conversation."""

    model_config = ConfigDict(extra="forbid")

    role: Literal["patient", "agent"]
    text: str


class AppointmentState(BaseModel):
    """An appointment as the core reported it, without the patient's identifier."""

    model_config = ConfigDict(extra="forbid")

    id: str
    status: str
    treatment: str
    practitioner: str
    starts_at: str


class Usage(BaseModel):
    """What the run cost. No money is spent: the figure is what the API would charge."""

    model_config = ConfigDict(extra="forbid")

    agent_calls: int = 0
    simulator_calls: int = 0
    input_tokens: int = 0
    output_tokens: int = 0
    estimated_cost_usd: float = 0.0
    agent_call_seconds: list[float] = []


class ClinicFacts(BaseModel):
    """What the clinic really offers, to check the replies against."""

    model_config = ConfigDict(extra="forbid")

    amounts: list[float]
    practitioners: list[str]
    opening_times: list[str]


class Recording(BaseModel):
    """Everything the graders need, so that CI can grade a run with no model and no core."""

    model_config = ConfigDict(extra="forbid")

    scenario_id: str
    fingerprint: str
    model: str
    language: str
    turns: list[Turn]
    core_results: list[str]
    trace: list[dict[str, Any]]
    seed_before: list[AppointmentState]
    appointments_after: list[AppointmentState]
    handed_over: bool
    clinic: ClinicFacts
    usage: Usage


def _strings(value: object) -> Iterator[str]:
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for item in value.values():
            yield from _strings(item)
    elif isinstance(value, list):
        for item in value:
            yield from _strings(item)


def assert_publishable(text: str) -> None:
    """Refuse a recording that holds private data.

    The model's context includes the account's email and the machine's paths, so a reply could carry
    them. Synthetic addresses on the reserved ``.example`` domain are fine.

    Args:
        text: The recording, serialized.

    Raises:
        UnsafeRecordingError: If it contains an email, a system path or a token.
    """
    for match in _EMAIL.finditer(text):
        if not match.group().endswith(".example"):
            raise UnsafeRecordingError("The recording contains an email address")
    if _SYSTEM_PATH.search(text):
        raise UnsafeRecordingError("The recording contains a path of this machine")
    if _TOKEN.search(text):
        raise UnsafeRecordingError("The recording contains a token")


def recording_path(scenario_id: str, folder: Path = RECORDINGS) -> Path:
    """Say where a scenario's recording lives.

    Args:
        scenario_id: The scenario.
        folder: The folder with the recordings.

    Returns:
        The path of its file.
    """
    return folder / f"{scenario_id}.json"


def save_recording(recording: Recording, folder: Path = RECORDINGS) -> Path:
    """Write a recording, after checking that it can be published.

    Args:
        recording: The recording.
        folder: The folder with the recordings.

    Returns:
        The path of the file.

    Raises:
        UnsafeRecordingError: If it holds private data, in which case nothing is written.
    """
    # Each string is checked as it is, because once in JSON a line break after a colon looks like
    # the start of a Windows path.
    for value in _strings(recording.model_dump(mode="json")):
        assert_publishable(value)
    text = recording.model_dump_json(indent=2) + "\n"
    folder.mkdir(parents=True, exist_ok=True)
    path = recording_path(recording.scenario_id, folder)
    path.write_text(text, encoding="utf-8", newline="\n")
    return path


def load_recording(scenario_id: str, folder: Path = RECORDINGS) -> Recording | None:
    """Read a scenario's recording.

    Args:
        scenario_id: The scenario.
        folder: The folder with the recordings.

    Returns:
        The recording, if there is one.
    """
    path = recording_path(scenario_id, folder)
    if not path.exists():
        return None
    return Recording.model_validate_json(path.read_text(encoding="utf-8"))
