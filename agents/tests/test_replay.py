"""Tests of the public page of recorded conversations.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import json
import re

from evals.replay import DATA, SITE, render_data
from evals.scenarios import load_bank


def _payload() -> dict[str, object]:
    text = render_data(load_bank())
    assert text.startswith("window.TURNO_REPLAY = ")
    loaded: dict[str, object] = json.loads(
        text.removeprefix("window.TURNO_REPLAY = ").rstrip(";\n")
    )
    return loaded


def test_the_page_is_up_to_date_with_the_recordings() -> None:
    """A stale page would publish results that are no longer true."""
    assert DATA.read_text(encoding="utf-8") == render_data(load_bank())


def test_it_holds_the_failures_too() -> None:
    """A page of successes only would be an advert, not a measurement."""
    conversations = _payload()["conversations"]
    assert isinstance(conversations, list)
    assert any(not conversation["passed"] for conversation in conversations)
    assert any(conversation["passed"] for conversation in conversations)


def test_nothing_in_the_data_can_close_its_script() -> None:
    """Simulated patients try to break out of whatever holds their words."""
    text = render_data(load_bank())

    assert "<" not in text
    assert ">" not in text
    assert "&" not in text


def test_the_page_never_writes_a_recording_as_markup() -> None:
    """Everything goes through textContent, so an attack in a message stays text."""
    script = (SITE / "replay.js").read_text(encoding="utf-8")

    assert not re.search(r"innerHTML|outerHTML|insertAdjacentHTML|document\.write|eval\(", script)


def test_the_page_runs_only_its_own_files() -> None:
    """Its policy allows nothing from elsewhere and no inline code."""
    page = (SITE / "index.html").read_text(encoding="utf-8")

    assert "default-src 'none'" in page
    assert "script-src 'self'" in page
    assert "unsafe" not in page
    assert not re.search(r"<script(?![^>]*\bsrc=)", page)
    assert not re.search(r"\son[a-z]+=", page)
