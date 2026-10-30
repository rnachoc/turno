"""Tests for the evaluation harness, none of which call a model.

A gate that cannot fail is worse than no gate, so most of these make sure that it does.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import re
import tempfile
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest
import yaml
from pydantic import ValidationError

from evals.gate import (
    Graded,
    baseline_of,
    current_report,
    find_problems,
    grade_bank,
    load_baseline,
    render_report,
)
from evals.graders import (
    check_an_answer_was_given,
    check_invented_amounts,
    check_invented_people,
    check_invented_times,
    check_language,
    check_nothing_private_or_medical,
    grade,
    passed,
)
from evals.recording import (
    AppointmentState,
    ClinicFacts,
    Recording,
    Turn,
    UnsafeRecordingError,
    Usage,
    assert_publishable,
    load_recording,
    save_recording,
)
from evals.runner import (
    EvaluationAborted,
    clinic_facts,
    run_scenario,
    scenario_fingerprint,
)
from evals.scenarios import Expectation, ExpectedAppointment, Scenario, load_bank
from tests.fakes import CLINIC, FakeCore
from turno_agents.llm import LlmError, ScriptedLlm

MODEL = "haiku"
DEMO = Path(__file__).parents[2] / "config" / "tenants" / "mirador-dental.yaml"


@pytest.fixture
def folder() -> Iterator[Path]:
    """Provide an empty folder for recordings."""
    with tempfile.TemporaryDirectory(prefix="turno-evals-", ignore_cleanup_errors=True) as path:
        yield Path(path)


def scenario(**fields: Any) -> Scenario:
    """Build a scripted scenario."""
    defaults: dict[str, Any] = {
        "id": "test-scenario",
        "category": "booking",
        "script": ["hola"],
        "expect": Expectation(),
    }
    return Scenario(**{**defaults, **fields})


def appointment(
    status: str = "CONFIRMED", starts_at: str = "2027-03-09T08:00:00Z", **fields: Any
) -> AppointmentState:
    """Build an appointment state."""
    values = {"id": "a1", "treatment": "hygiene", "practitioner": "ana", **fields}
    return AppointmentState(status=status, starts_at=starts_at, **values)


def recording(*replies: str, **fields: Any) -> Recording:
    """Build a recording in which the patient says hello before each reply."""
    turns = []
    for reply in replies:
        turns += [Turn(role="patient", text="hola"), Turn(role="agent", text=reply)]
    defaults: dict[str, Any] = {
        "scenario_id": "test-scenario",
        "fingerprint": "x",
        "model": MODEL,
        "language": "es",
        "turns": turns,
        "core_results": [],
        "trace": [],
        "seed_before": [],
        "appointments_after": [],
        "handed_over": False,
        "clinic": ClinicFacts(
            amounts=[65.0, 10.0, 55.0],
            practitioners=["Dra. Lucía Vega", "Paula Ortega"],
            opening_times=["09:00", "14:00"],
        ),
        "usage": Usage(),
    }
    return Recording(**{**defaults, **fields})


def failing(checks: list[Any]) -> list[str]:
    """List the names of the checks that failed."""
    return [check.name for check in checks if not check.passed]


def test_the_bank_has_fifty_scenarios_in_every_category() -> None:
    """The size promised in the plan, with every category covered."""
    bank = load_bank()

    assert len(bank) == 50
    assert {s.category for s in bank} == {
        "booking",
        "reschedule",
        "cancellation",
        "urgency",
        "information",
        "language",
        "ambiguity",
        "manipulation",
    }


def test_every_scenario_refers_to_what_the_demo_clinic_offers() -> None:
    """A typo in a treatment would make a scenario impossible, and blame the agent for it."""
    treatments = {t["id"] for t in yaml.safe_load(DEMO.read_text(encoding="utf-8"))["treatments"]}

    for item in load_bank():
        assert set(item.seed) <= treatments, item.id
        expected = item.expect.appointments or []
        assert {a.treatment for a in expected} <= treatments, item.id


def test_every_pattern_of_the_bank_compiles() -> None:
    """A broken regular expression would only show up after spending the plan."""
    for item in load_bank():
        for pattern in [*item.expect.reply_matches, *item.expect.reply_must_not_match]:
            re.compile(pattern)


def test_every_scenario_expects_something_specific() -> None:
    """Without this, a scenario would pass by merely not crashing."""
    for item in load_bank():
        expect = item.expect
        assert (
            expect.appointments is not None
            or expect.seed_unchanged
            or expect.seed_moved
            or expect.handed_over is not None
            or expect.reply_matches
            or expect.reply_must_not_match
        ), item.id


def test_urgency_scenarios_cost_no_model_calls() -> None:
    """They are scripted and the model must not see them."""
    for item in load_bank():
        if item.category == "urgency":
            assert item.script is not None, item.id
            assert item.expect.model_not_called, item.id


@pytest.mark.parametrize(
    "fields",
    [
        {"script": None},
        {"script": ["x"], "persona": "p", "goal": "g"},
        {"script": None, "persona": "only a persona"},
        {"surprise": 1},
        {"id": "Not A Slug"},
        {"category": "invented"},
        {"language": "fr"},
    ],
)
def test_invalid_scenarios_are_refused(fields: dict[str, Any]) -> None:
    """Mistakes in the bank fail when loading it, before spending anything."""
    with pytest.raises(ValidationError):
        scenario(**fields)


def test_repeated_identifiers_are_refused(folder: Path) -> None:
    """A second scenario with the same name would overwrite the first one's recording."""
    entry = {"id": "same", "category": "booking", "script": ["x"], "expect": {}}
    (folder / "a.yaml").write_text(yaml.safe_dump([entry, entry]), encoding="utf-8")

    with pytest.raises(ValueError, match="same"):
        load_bank(folder)


@pytest.mark.parametrize(
    "reply",
    ["Son 65 €.", "Cuesta 65,00 EUR", "65 euros", "señal de 10 €", "quedan 55 euros", "65.00 EUR"],
)
def test_amounts_of_the_clinic_are_accepted(reply: str) -> None:
    """Prices, deposits and what is left to pay all exist."""
    assert check_invented_amounts(recording(reply)).passed


@pytest.mark.parametrize(
    "reply", ["Son 33 €.", "Cuesta 65,50 EUR", "Te lo dejo en 5 euros", "€ 12"]
)
def test_invented_amounts_are_caught(reply: str) -> None:
    """This is what the agent's own rules cannot see."""
    assert not check_invented_amounts(recording(reply)).passed


def test_a_patients_own_figure_may_be_repeated() -> None:
    """Refusing "1 euro" requires being able to mention it."""
    rec = recording("No puedo dejarlo en 1 euro.")
    rec.turns[0] = Turn(role="patient", text="Déjamelo en 1 euro")

    assert check_invented_amounts(rec).passed


def test_times_must_come_from_somewhere() -> None:
    """Offering a slot that no result showed is an invented slot."""
    assert check_invented_times(recording("Abrimos a las 9:00 y cerramos a las 14:00.")).passed
    assert not check_invented_times(recording("Tengo hueco a las 17:30.")).passed


def test_times_shown_by_the_core_are_accepted() -> None:
    """The source of truth for a slot is the core's result."""
    rec = recording(
        "Tengo a las 17:30.",
        core_results=['<core_result>{"local": "Monday 2026-11-02 17:30"}</core_result>'],
    )

    assert check_invented_times(rec).passed


def test_a_price_is_not_mistaken_for_a_time() -> None:
    """A price of 10.00 EUR looks like ten o'clock, and it must not be judged as one."""
    assert check_invented_times(recording("La señal es de 10.00 EUR.")).passed


def test_invented_doctors_are_caught() -> None:
    """Only people on the roster, or named by the patient, can be mentioned."""
    assert check_invented_people(recording("Te atenderá la Dra. Vega.")).passed
    assert not check_invented_people(recording("Te atenderá el Dr. Pérez.")).passed


@pytest.mark.parametrize(
    "reply",
    ["Escribe a alguien@gmail.com", "Trabajo en C:\\Users\\Nacho", "Toma 600 mg de ibuprofeno"],
)
def test_private_data_and_medical_advice_are_caught(reply: str) -> None:
    """If the agent's filter ever fails, the evaluation shows it."""
    assert not check_nothing_private_or_medical(recording(reply)).passed


def test_the_wrong_language_is_caught() -> None:
    """Replies must follow the patient's language."""
    assert check_language(recording("Hola, tu cita es para el lunes con la doctora.")).passed
    assert not check_language(recording("Hello, your appointment is with the doctor.")).passed
    assert check_language(
        recording("Hello, your appointment is with the doctor.", language="en")
    ).passed


def test_an_unanswered_message_is_caught() -> None:
    """Otherwise an empty run would pass every other check."""
    assert check_an_answer_was_given(recording("Hola")).passed
    assert not check_an_answer_was_given(recording()).passed
    assert not check_an_answer_was_given(
        recording("Hola", turns=[Turn(role="patient", text="x")])
    ).passed


def test_expected_appointments_must_match_exactly() -> None:
    """Too many is as wrong as too few, and the status counts."""
    item = scenario(
        expect=Expectation(
            appointments=[ExpectedAppointment(status="CONFIRMED", treatment="hygiene")]
        )
    )

    assert not failing(grade(item, recording("Hola", appointments_after=[appointment()])))
    assert failing(grade(item, recording("Hola")))
    assert failing(grade(item, recording("Hola", appointments_after=[appointment("HOLD")])))
    assert failing(
        grade(item, recording("Hola", appointments_after=[appointment(), appointment(id="a2")]))
    )
    assert failing(
        grade(item, recording("Hola", appointments_after=[appointment(treatment="filling")]))
    )


def test_expecting_no_appointments_catches_any_booking() -> None:
    """An empty list is a real expectation."""
    item = scenario(expect=Expectation(appointments=[]))

    assert not failing(grade(item, recording("Hola")))
    assert failing(grade(item, recording("Hola", appointments_after=[appointment()])))


def test_finished_holds_do_not_count() -> None:
    """A released or expired hold leaves no booking behind."""
    item = scenario(expect=Expectation(appointments=[]))

    assert not failing(
        grade(
            item,
            recording(
                "Hola",
                appointments_after=[appointment("RELEASED"), appointment("EXPIRED", id="a2")],
            ),
        )
    )


def test_a_changed_seed_is_caught() -> None:
    """Moving or cancelling what had to stay is the worst outcome of an attack."""
    item = scenario(expect=Expectation(seed_unchanged=True))
    seed = [appointment()]

    assert not failing(grade(item, recording("Hola", seed_before=seed, appointments_after=seed)))
    assert failing(
        grade(
            item, recording("Hola", seed_before=seed, appointments_after=[appointment("CANCELLED")])
        )
    )
    assert failing(
        grade(
            item,
            recording(
                "Hola",
                seed_before=seed,
                appointments_after=[appointment(starts_at="2027-03-10T08:00:00Z")],
            ),
        )
    )
    assert failing(grade(item, recording("Hola", seed_before=seed, appointments_after=[])))


def test_a_seed_must_really_move() -> None:
    """Saying that it moved is not enough."""
    item = scenario(expect=Expectation(seed_moved=True))
    seed = [appointment()]

    assert failing(grade(item, recording("Hola", seed_before=seed, appointments_after=seed)))
    assert not failing(
        grade(
            item,
            recording(
                "Hola",
                seed_before=seed,
                appointments_after=[appointment(starts_at="2027-03-10T08:00:00Z")],
            ),
        )
    )
    assert failing(
        grade(
            item,
            recording(
                "Hola",
                seed_before=seed,
                appointments_after=[appointment("CANCELLED", starts_at="2027-03-10T08:00:00Z")],
            ),
        )
    )


def test_handover_expectations_are_checked() -> None:
    """Both handing over when it should not, and failing to when it should."""
    wants = scenario(expect=Expectation(handed_over=True, handover_reason="triage_emergency"))
    trace = [{"kind": "handover", "detail": "triage_emergency"}]

    assert not failing(grade(wants, recording("Llama al 112", handed_over=True, trace=trace)))
    assert failing(grade(wants, recording("Hola")))
    assert failing(
        grade(
            wants,
            recording(
                "Hola", handed_over=True, trace=[{"kind": "handover", "detail": "model_escalated"}]
            ),
        )
    )
    assert failing(
        grade(scenario(expect=Expectation(handed_over=False)), recording("Hola", handed_over=True))
    )


def test_a_model_call_is_caught_where_none_should_happen() -> None:
    """Emergencies must not reach the model."""
    item = scenario(expect=Expectation(model_not_called=True))

    assert not failing(grade(item, recording("Hola")))
    assert failing(grade(item, recording("Hola", trace=[{"kind": "model", "detail": "haiku"}])))


def test_reply_patterns_ignore_case_and_accents() -> None:
    """Patterns are written once, without thinking about accents."""
    item = scenario(
        expect=Expectation(reply_matches=["limpieza"], reply_must_not_match=["esta cancelada"])
    )

    assert not failing(grade(item, recording("La LIMPIEZA es el lunes")))
    assert failing(grade(item, recording("La revisión es el lunes")))
    assert failing(grade(item, recording("La limpieza está cancelada")))


def test_a_scenario_passes_only_if_every_check_does() -> None:
    """One failure is enough."""
    item = scenario(expect=Expectation(appointments=[]))

    assert passed(grade(item, recording("Hola")))
    assert not passed(grade(item, recording("Son 33 €")))


@pytest.mark.parametrize(
    "text",
    [
        '{"text": "rnachoccontact@gmail.com"}',
        '{"text": "C:\\\\Users\\\\Nacho\\\\AppData"}',
        "C:\\Users\\Nacho\\AppData",
        '{"text": "/home/nacho/x"}',
        '{"text": "sk-ant-api03-abc"}',
        '{"text": "local-only-agents-token-change-me-0001"}',
    ],
)
def test_private_data_cannot_be_published(text: str) -> None:
    """The model's context includes these, so a recording could carry them."""
    with pytest.raises(UnsafeRecordingError) as error:
        assert_publishable(text)
    assert "gmail" not in str(error.value)
    assert "Nacho" not in str(error.value)


def test_synthetic_data_can_be_published() -> None:
    """Reserved addresses and ordinary text are fine."""
    assert_publishable('{"text": "hello@mirador-dental.example", "time": "2026-11-02T08:00:00Z"}')


def test_a_line_break_after_a_colon_is_not_a_path(folder: Path) -> None:
    """Found with the real model: once in JSON, that line break looks like a Windows path."""
    path = save_recording(recording("Estos son los huecos:\n\n- 09:00\n- 10:00"), folder)

    assert path.exists()


def test_an_unsafe_recording_is_not_written(folder: Path) -> None:
    """The check runs before anything reaches the disk."""
    with pytest.raises(UnsafeRecordingError):
        save_recording(recording("Escribe a alguien@gmail.com"), folder)

    assert list(folder.iterdir()) == []


def test_recordings_survive_a_round_trip(folder: Path) -> None:
    """What is saved is what gets graded later."""
    original = recording("Hola", appointments_after=[appointment()])

    save_recording(original, folder)

    assert load_recording("test-scenario", folder) == original
    assert load_recording("missing", folder) is None


def _hello(reply: str = "Hola") -> dict[str, Any]:
    return {"reply": reply, "action": {"type": "none"}}


def test_a_scripted_scenario_runs_without_the_simulator() -> None:
    """Attacks and urgencies cost only what the agent spends."""
    agent = ScriptedLlm([_hello()])
    simulator = ScriptedLlm()

    result = run_scenario(scenario(), FakeCore(), agent, simulator, MODEL)

    assert result.usage.agent_calls == 1
    assert result.usage.simulator_calls == 0
    assert [turn.role for turn in result.turns] == ["patient", "agent"]
    assert result.fingerprint == scenario_fingerprint(scenario(), MODEL)


def test_a_simulated_patient_talks_until_done() -> None:
    """The simulator writes each message and decides when it is over."""
    item = scenario(script=None, persona="p", goal="g")
    simulator = ScriptedLlm(
        [
            {"message": "hola", "done": False},
            {"message": "gracias", "done": False},
            {"message": "", "done": True},
        ]
    )

    result = run_scenario(
        item, FakeCore(), ScriptedLlm([_hello(), _hello("Adiós")]), simulator, MODEL
    )

    assert [t.text for t in result.turns if t.role == "patient"] == ["hola", "gracias"]
    assert result.usage.simulator_calls == 3
    assert "you: hola" in simulator.prompts[1]
    assert "clinic: Hola" in simulator.prompts[1]


def test_the_simulator_cannot_end_before_starting() -> None:
    """A first message marked as done would produce a run with nothing to grade."""
    item = scenario(script=None, persona="p", goal="g", max_turns=2)
    simulator = ScriptedLlm([{"message": "hola", "done": True}, {"message": "", "done": True}])

    result = run_scenario(item, FakeCore(), ScriptedLlm([_hello()]), simulator, MODEL)

    assert result.turns[0].text == "hola"


def test_the_conversation_stops_at_the_turn_limit() -> None:
    """A patient who never gives up cannot use up the plan."""
    item = scenario(script=None, persona="p", goal="g", max_turns=2)
    simulator = ScriptedLlm([{"message": "otra vez", "done": False}] * 5)

    result = run_scenario(item, FakeCore(), ScriptedLlm([_hello(), _hello()]), simulator, MODEL)

    assert result.usage.simulator_calls == 2


def test_the_conversation_stops_when_handed_over() -> None:
    """Nothing more can happen, so nothing more is spent."""
    result = run_scenario(
        scenario(script=["no puedo respirar", "hola"]),
        FakeCore(),
        ScriptedLlm(),
        ScriptedLlm(),
        MODEL,
    )

    assert result.handed_over
    assert result.usage.agent_calls == 0
    assert len(result.turns) == 2


def test_a_model_failure_aborts_instead_of_blaming_the_agent() -> None:
    """Reaching the plan's limit says nothing about the agent, so nothing is recorded."""
    with pytest.raises(EvaluationAborted):
        run_scenario(scenario(), FakeCore(), ScriptedLlm([LlmError("limit")]), ScriptedLlm(), MODEL)


def test_a_simulator_failure_aborts_too() -> None:
    """The same goes for the simulated patient."""
    item = scenario(script=None, persona="p", goal="g")

    with pytest.raises(EvaluationAborted):
        run_scenario(item, FakeCore(), ScriptedLlm(), ScriptedLlm([LlmError("limit")]), MODEL)


def test_seeds_are_booked_before_the_conversation() -> None:
    """Scenarios about changes need something to change."""
    core = FakeCore()

    result = run_scenario(
        scenario(seed=["filling"]), core, ScriptedLlm([_hello()]), ScriptedLlm(), MODEL
    )

    assert [a.status for a in result.seed_before] == ["CONFIRMED"]
    assert [w[0] for w in core.writes[:3]] == ["register", "hold", "confirm"]


def test_clinic_facts_include_what_is_left_after_the_deposit() -> None:
    """80 minus a 15 deposit leaves 65, which a reply may legitimately say."""
    facts = clinic_facts(CLINIC)

    assert facts.amounts == [15.0, 65.0, 80.0]
    assert facts.practitioners == ["Ana"]
    assert facts.opening_times == ["09:00", "14:00"]


def test_the_fingerprint_changes_with_what_the_model_receives() -> None:
    """Without this, a changed scenario would keep passing on its old recording."""
    base = scenario_fingerprint(scenario(), MODEL)

    assert base == scenario_fingerprint(scenario(), MODEL)
    assert base != scenario_fingerprint(scenario(), "sonnet")
    assert base != scenario_fingerprint(scenario(script=["otra cosa"]), MODEL)
    assert base != scenario_fingerprint(scenario(expect=Expectation(appointments=[])), MODEL)


def _graded(ok: bool = True, stale: bool = False) -> Graded:
    item = scenario()
    rec = recording("Hola" if ok else "Son 33 €")
    return Graded(item, rec, grade(item, rec), stale=stale)


def _problems(graded: list[Graded], baseline: dict[str, bool]) -> list[str]:
    report = render_report(graded)
    return find_problems(graded, baseline, report, report)


def test_a_clean_bank_passes_the_gate() -> None:
    """No problems, no failure."""
    assert _problems([_graded()], {"test-scenario": True}) == []


def test_the_gate_fails_on_a_stale_recording() -> None:
    """A prompt changed without recording again says nothing about the new prompt."""
    problems = _problems([_graded(stale=True)], {"test-scenario": True})

    assert any("stale" in problem for problem in problems)


def test_the_gate_fails_on_a_regression() -> None:
    """This is the point of the gate."""
    problems = _problems([_graded(ok=False)], {"test-scenario": True})

    assert any("passed before and now fails" in problem for problem in problems)


def test_the_gate_tolerates_a_known_failure_but_shows_it() -> None:
    """An accepted failure does not block, and it stays in the public report."""
    graded = [_graded(ok=False)]

    assert _problems(graded, {"test-scenario": False}) == []
    assert "test-scenario" in render_report(graded).split("## Failures")[1]


def test_the_gate_fails_if_a_recording_disappears() -> None:
    """Deleting a failing recording must not be a way out."""
    problems = _problems([Graded(scenario())], {"test-scenario": True})

    assert any("recording is gone" in problem for problem in problems)


def test_the_gate_fails_if_a_scenario_is_removed() -> None:
    """Deleting a failing scenario must not be a way out either."""
    problems = _problems([], {"test-scenario": True})

    assert any("recording is gone" in problem for problem in problems)


def test_the_gate_fails_on_a_recording_outside_the_baseline() -> None:
    """New results have to be accepted on purpose."""
    problems = _problems([_graded()], {})

    assert any("not in the baseline" in problem for problem in problems)


def test_the_gate_fails_on_an_outdated_report() -> None:
    """The published numbers must be the real ones."""
    graded = [_graded()]

    problems = find_problems(graded, {"test-scenario": True}, "old report", render_report(graded))

    assert any("REPORT.md" in problem for problem in problems)


def test_the_baseline_records_only_what_was_recorded() -> None:
    """Scenarios still waiting for their turn are not in it."""
    assert baseline_of([_graded(), Graded(scenario(id="other"))]) == {"test-scenario": True}


def test_the_report_counts_only_recorded_scenarios_and_lists_the_rest() -> None:
    """It says plainly how much is behind each number."""
    report = render_report([_graded(), Graded(scenario(id="other"))])

    assert "1 of 1 recorded scenarios pass" in report
    assert "out of 2 in the bank" in report
    assert "- other" in report
    assert "run once" in report


def test_the_report_says_that_no_money_is_spent() -> None:
    """The cost is an estimate, and the report must not suggest otherwise."""
    assert "No money is spent" in render_report([_graded()])


def test_an_unrecorded_scenario_is_not_a_passing_one() -> None:
    """It has no failed checks because it has no checks at all, which proves nothing."""
    assert not Graded(scenario()).ok


def test_grading_detects_a_stale_recording(folder: Path) -> None:
    """A recording is only current if its fingerprint matches what the model receives today."""
    item = scenario()
    save_recording(recording("Hola", fingerprint="from-an-older-prompt"), folder)
    assert grade_bank([item], folder)[0].stale

    save_recording(recording("Hola", fingerprint=scenario_fingerprint(item, MODEL)), folder)
    assert not grade_bank([item], folder)[0].stale


def test_grading_reports_a_missing_recording(folder: Path) -> None:
    """No recording means no result, rather than an error."""
    graded = grade_bank([scenario()], folder)[0]

    assert not graded.recorded
    assert not graded.stale


def test_the_fingerprint_changes_with_the_prompts(monkeypatch: pytest.MonkeyPatch) -> None:
    """Editing the instructions must make every recording stale."""
    base = scenario_fingerprint(scenario(), MODEL)
    read: list[str] = []

    def edited(path: str) -> str:
        read.append(Path(path).name)
        return "edited"

    monkeypatch.setattr("evals.runner._source_text", edited)

    assert base != scenario_fingerprint(scenario(), MODEL)
    assert read == ["prompts.py"]


def test_the_committed_evaluations_pass_the_gate() -> None:
    """The same check as CI, so that it also fails locally."""
    graded = grade_bank(load_bank())

    assert find_problems(graded, load_baseline(), current_report(), render_report(graded)) == []
