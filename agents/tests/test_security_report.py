"""Tests of how the attack bank tells a defence from a breach, with no stack running.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

from typing import cast

import httpx2
import pytest

from security.agent_attacks import agent_attacks
from security.bank import Attack, Breach, Stack, Stopper, code_of, must_be, refused_with
from security.core_attacks import core_attacks
from security.report import Result, render, run_attack

NO_STACK = cast(Stack, None)


def _attack(run: object, goal: str = "Do something bad") -> Attack:
    return Attack("x-01", "Things", goal, run)  # type: ignore[arg-type]


def test_a_stopped_attack_says_what_stopped_it() -> None:
    """The defence goes in the result."""
    result = run_attack(_attack(lambda _: Stopper.RULE), NO_STACK)

    assert result.stopper is Stopper.RULE
    assert result.note == ""


def test_a_breach_is_reported_as_one() -> None:
    """Getting through is the one thing that must never pass unnoticed."""

    def run(_: Stack) -> Stopper:
        raise Breach("it got through")

    result = run_attack(_attack(run), NO_STACK)

    assert result.stopper is None
    assert result.note == "it got through"


@pytest.mark.parametrize(
    "error",
    [
        httpx2.ConnectError("down"),
        KeyError("id"),
        ValueError("not json"),
        IndexError(),
        TypeError(),
    ],
)
def test_an_attack_that_cannot_finish_does_not_pass_for_a_defence(error: Exception) -> None:
    """A stack that is down must not produce a clean report."""

    def run(_: Stack) -> Stopper:
        raise error

    result = run_attack(_attack(run), NO_STACK)

    assert result.stopper is None
    assert "could not finish" in result.note


def _response(status: int, body: object = None) -> httpx2.Response:
    return httpx2.Response(status, json=body or {})


def test_an_unexpected_status_is_a_breach() -> None:
    """Above all an error 500, which means the input got further than it should."""
    must_be(_response(400), 400, 404)
    with pytest.raises(Breach, match="500"):
        must_be(_response(500), 400)


def test_being_refused_for_another_reason_is_a_breach() -> None:
    """A 404 from a route that does not exist would otherwise look like a defence."""
    refused_with(_response(404, {"code": "NOT_FOUND"}), 404, "NOT_FOUND")
    with pytest.raises(Breach, match="OTHER"):
        refused_with(_response(404, {"code": "OTHER"}), 404, "NOT_FOUND")
    with pytest.raises(Breach):
        refused_with(_response(200, {"code": "NOT_FOUND"}), 404, "NOT_FOUND")


def test_a_refusal_that_is_not_json_has_no_code() -> None:
    """An HTML page from the server is not a refusal of the core."""
    assert code_of(httpx2.Response(400, text="<html>")) == ""


def _results() -> list[Result]:
    return [
        Result(Attack("a", "One", "First goal", lambda _: Stopper.RULE), Stopper.RULE),
        Result(Attack("b", "One", "Second goal", lambda _: Stopper.RULE), Stopper.RULE),
        Result(Attack("c", "Two", "Third goal", lambda _: Stopper.GUARD), Stopper.GUARD),
    ]


def test_the_report_adds_up() -> None:
    """Totals, defences and categories."""
    report = render(_results())

    assert "**3 of 3 attacks stopped.**" in report
    assert "| Rule of the core | 2 |" in report
    assert "| Guard on the reply | 1 |" in report
    assert "## One" in report
    assert "| Third goal | Guard on the reply |" in report


def test_a_breach_shows_in_the_report() -> None:
    """A report never hides one."""
    results = [*_results(), Result(_attack(None), None, "it got through")]

    report = render(results)

    assert "**3 of 4 attacks stopped.**" in report
    assert "**NOT STOPPED**: it got through" in report


def test_the_report_changes_only_if_what_stops_an_attack_changes() -> None:
    """CI compares it with the committed one, so it must hold nothing that varies between runs."""
    assert render(_results()) == render(_results())


def test_identifiers_and_goals_are_unique() -> None:
    """Two attacks with one name could not be told apart in the report."""
    bank = [*core_attacks(), *agent_attacks()]

    assert len({attack.id for attack in bank}) == len(bank)
    assert len({attack.goal for attack in bank}) == len(bank)
    assert len(bank) > 60
