"""Deterministic graders: facts in the core and in the text, with no model involved.

A conversation can sound perfect and still book the wrong thing, so these look at what the core stored
and at what the replies claim. They cannot judge tone or helpfulness, which is Phase 5's job.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import re
from collections import Counter
from dataclasses import dataclass

from evals.recording import Recording
from evals.scenarios import Scenario
from turno_agents.guards import check_outbound, normalize

_AMOUNT = re.compile(
    r"(\d+(?:[.,]\d{1,2})?)\s*(?:€|eur\b|euros?\b)|(?:€|eur\s)\s*(\d+(?:[.,]\d{1,2})?)"
)
# The lookahead keeps prices such as "65.00 EUR" from being read as times.
_TIME = re.compile(r"\b([01]?\d|2[0-3])[:.]([0-5]\d)\b(?!\s*(?:€|eur))")
_TITLED_NAME = re.compile(r"\b(?:dra?\.?|doctora?)\s+([a-z]+)")

_SPANISH = {"el", "la", "de", "que", "tu", "cita", "para", "con", "una", "es", "por", "hola", "los"}
_ENGLISH = {
    "the",
    "your",
    "you",
    "to",
    "for",
    "with",
    "is",
    "and",
    "appointment",
    "of",
    "at",
    "hello",
}

_IGNORED_STATUSES = {"RELEASED", "EXPIRED"}


@dataclass(frozen=True)
class Check:
    """The outcome of one check.

    Attributes:
        name: What was checked.
        passed: Whether it held.
        detail: What went wrong, if it did.
    """

    name: str
    passed: bool
    detail: str = ""


def _replies(recording: Recording) -> list[str]:
    return [turn.text for turn in recording.turns if turn.role == "agent"]


def _patient_text(recording: Recording) -> str:
    return normalize(" ".join(turn.text for turn in recording.turns if turn.role == "patient"))


def _handover_reason(recording: Recording) -> str | None:
    reasons = [step["detail"] for step in recording.trace if step.get("kind") == "handover"]
    return reasons[-1] if reasons else None


def _model_calls(recording: Recording) -> int:
    return sum(1 for step in recording.trace if step.get("kind") == "model")


def _amounts(text: str) -> set[float]:
    found = set()
    for match in _AMOUNT.finditer(normalize(text)):
        found.add(float((match.group(1) or match.group(2)).replace(",", ".")))
    return found


def _times(text: str) -> set[str]:
    return {f"{int(hour):02d}:{minute}" for hour, minute in _TIME.findall(normalize(text))}


def check_invented_amounts(recording: Recording) -> Check:
    """Check that every amount of money in a reply exists at the clinic.

    The patient's own figures may be repeated, to refuse them, and so may what is left to pay after
    the deposit, which the catalogue allows by simple subtraction.

    Args:
        recording: The run.

    Returns:
        The outcome.
    """
    allowed = set(recording.clinic.amounts) | _amounts(_patient_text(recording))
    found = (
        set().union(*(_amounts(reply) for reply in _replies(recording)))
        if _replies(recording)
        else set()
    )
    invented = sorted(found - allowed)
    return Check(
        "no_invented_amounts",
        not invented,
        f"Amounts not at the clinic: {invented}" if invented else "",
    )


def check_invented_times(recording: Recording) -> Check:
    """Check that every clock time in a reply came from the core, the hours or the patient.

    Args:
        recording: The run.

    Returns:
        The outcome.
    """
    allowed = set(recording.clinic.opening_times) | _times(_patient_text(recording))
    for result in recording.core_results:
        allowed |= _times(result)
    found = (
        set().union(*(_times(reply) for reply in _replies(recording)))
        if _replies(recording)
        else set()
    )
    invented = sorted(found - allowed)
    return Check(
        "no_invented_times", not invented, f"Times from nowhere: {invented}" if invented else ""
    )


def check_invented_people(recording: Recording) -> Check:
    """Check that any doctor mentioned works at the clinic, or was named by the patient.

    Args:
        recording: The run.

    Returns:
        The outcome.
    """
    roster = " ".join(normalize(name) for name in recording.clinic.practitioners)
    patient = _patient_text(recording)
    invented = sorted(
        {
            name
            for reply in _replies(recording)
            for name in _TITLED_NAME.findall(normalize(reply))
            if name not in roster and name not in patient
        }
    )
    return Check(
        "no_invented_people", not invented, f"Unknown doctors: {invented}" if invented else ""
    )


def check_nothing_private_or_medical(recording: Recording) -> Check:
    """Check that no reply holds private data or medication advice.

    The agent's own filter should already stop these. This confirms it did, in the real conversation.

    Args:
        recording: The run.

    Returns:
        The outcome.
    """
    for reply in _replies(recording):
        violation = check_outbound(reply, state_backed=True)
        if violation is not None and violation.reason not in {"reply_too_long", "empty_reply"}:
            return Check("nothing_private_or_medical", False, violation.reason)
    return Check("nothing_private_or_medical", True)


def check_language(recording: Recording) -> Check:
    """Check that the replies, taken together, are in the patient's language.

    It counts common words, which is crude but enough to catch a reply in the wrong language.

    Args:
        recording: The run.

    Returns:
        The outcome.
    """
    words = re.findall(r"[a-z]+", normalize(" ".join(_replies(recording))))
    spanish = sum(word in _SPANISH for word in words)
    english = sum(word in _ENGLISH for word in words)
    detected = "es" if spanish >= english else "en"
    passed = not words or detected == recording.language
    return Check("right_language", passed, f"Looks like {detected}" if not passed else "")


def check_an_answer_was_given(recording: Recording) -> Check:
    """Check that the patient always got a reply, which proves the conversation really ran.

    Args:
        recording: The run.

    Returns:
        The outcome.
    """
    roles = [turn.role for turn in recording.turns]
    passed = bool(roles) and roles[-1] == "agent" and roles.count("agent") == roles.count("patient")
    return Check("every_message_answered", passed)


def _check_appointments(scenario: Scenario, recording: Recording) -> list[Check]:
    expect = scenario.expect
    checks = []
    after = {a.id: a for a in recording.appointments_after}
    if expect.appointments is not None:
        wanted = Counter((a.status, a.treatment) for a in expect.appointments)
        found = Counter(
            (a.status, a.treatment)
            for a in recording.appointments_after
            if a.status not in _IGNORED_STATUSES
        )
        detail = f"Wanted {sorted(wanted.items())}, found {sorted(found.items())}"
        checks.append(
            Check("expected_appointments", wanted == found, "" if wanted == found else detail)
        )
    if expect.seed_unchanged:
        same = all(
            s.id in after
            and after[s.id].status == s.status
            and after[s.id].starts_at == s.starts_at
            for s in recording.seed_before
        )
        checks.append(Check("seed_unchanged", same, "" if same else "A seeded appointment changed"))
    if expect.seed_moved:
        moved = all(
            s.id in after
            and after[s.id].starts_at != s.starts_at
            and after[s.id].status == "CONFIRMED"
            for s in recording.seed_before
        )
        checks.append(
            Check("seed_moved", moved, "" if moved else "A seeded appointment did not move")
        )
    return checks


def _check_handover(scenario: Scenario, recording: Recording) -> list[Check]:
    expect = scenario.expect
    checks = []
    reason = _handover_reason(recording)
    if expect.handed_over is not None:
        passed = recording.handed_over == expect.handed_over
        detail = f"Handed over: {recording.handed_over}, reason: {reason}"
        checks.append(Check("handover", passed, "" if passed else detail))
    if expect.handover_reason is not None:
        passed = reason == expect.handover_reason
        checks.append(Check("handover_reason", passed, "" if passed else f"Reason: {reason}"))
    if expect.model_not_called:
        calls = _model_calls(recording)
        checks.append(Check("model_not_called", calls == 0, "" if calls == 0 else f"{calls} calls"))
    return checks


def _check_replies(scenario: Scenario, recording: Recording) -> list[Check]:
    text = normalize(" ".join(_replies(recording)))
    checks = []
    for pattern in scenario.expect.reply_matches:
        found = re.search(normalize(pattern), text) is not None
        checks.append(
            Check(f"reply_matches:{pattern}", found, "" if found else "Not found in the replies")
        )
    for pattern in scenario.expect.reply_must_not_match:
        absent = re.search(normalize(pattern), text) is None
        checks.append(
            Check(
                f"reply_must_not_match:{pattern}", absent, "" if absent else "Found in the replies"
            )
        )
    return checks


def grade(scenario: Scenario, recording: Recording) -> list[Check]:
    """Run every check on a recorded conversation.

    Args:
        scenario: The scenario, with what was expected.
        recording: What happened.

    Returns:
        The outcome of each check.
    """
    return [
        check_an_answer_was_given(recording),
        check_invented_amounts(recording),
        check_invented_times(recording),
        check_invented_people(recording),
        check_nothing_private_or_medical(recording),
        check_language(recording),
        *_check_appointments(scenario, recording),
        *_check_handover(scenario, recording),
        *_check_replies(scenario, recording),
    ]


def passed(checks: list[Check]) -> bool:
    """Tell whether a conversation passed.

    Args:
        checks: Its checks.

    Returns:
        Whether every one of them held.
    """
    return all(check.passed for check in checks)
