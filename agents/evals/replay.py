"""Builds a public page of the recorded conversations, which anyone can read with nothing running.

It only holds what the recordings hold, and those are checked before being saved. Each conversation is
data for the page, which writes it as text, so what a simulated patient wrote cannot run as code.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import json
from pathlib import Path
from typing import Any

from evals.gate import Graded, grade_bank
from evals.recording import assert_publishable
from evals.scenarios import Scenario

SITE = Path(__file__).parents[2] / "docs" / "replay"
DATA = SITE / "data.js"


def _steps(trace: list[dict[str, Any]]) -> list[dict[str, str]]:
    return [
        {"kind": str(step.get("kind", "")), "detail": str(step.get("detail", ""))} for step in trace
    ]


def _entry(item: Graded) -> dict[str, Any] | None:
    recording = item.recording
    if recording is None or item.stale:
        return None
    return {
        "id": item.scenario.id,
        "category": item.scenario.category,
        "language": recording.language,
        "passed": item.ok,
        "handedOver": recording.handed_over,
        "turns": [{"role": turn.role, "text": turn.text} for turn in recording.turns],
        "steps": _steps(recording.trace),
        "appointments": [
            {"status": appointment.status, "treatment": appointment.treatment}
            for appointment in recording.appointments_after
        ],
        "checks": [
            {"name": check.name, "passed": check.passed, "detail": check.detail}
            for check in item.checks
        ],
        "calls": recording.usage.agent_calls,
        "seconds": round(sum(recording.usage.agent_call_seconds), 1),
    }


def render_data(bank: list[Scenario]) -> str:
    """Write the conversations as a script the page loads.

    Args:
        bank: The scenarios.

    Returns:
        The content of the file, which never changes unless the recordings do.
    """
    entries = [entry for entry in map(_entry, grade_bank(bank)) if entry is not None]
    payload = {"total": len(bank), "conversations": entries}
    text = json.dumps(payload, ensure_ascii=False, indent=1, sort_keys=True)
    assert_publishable(text)
    # Nothing in a string may close the script or open a comment.
    safe = text.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    return f"window.TURNO_REPLAY = {safe};\n"
