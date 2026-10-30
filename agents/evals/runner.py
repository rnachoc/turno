"""Runs scenarios against the real agent and the real core, and records what happens.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import hashlib
import json
import re
import secrets
from datetime import date, timedelta
from pathlib import Path
from typing import Any

import turno_agents.prompts as prompts_module
from evals.recording import (
    AppointmentState,
    ClinicFacts,
    Recording,
    Turn,
    Usage,
)
from evals.scenarios import Scenario
from turno_agents.agent import AgentService
from turno_agents.conversation import Conversation, ConversationState, trace_view
from turno_agents.core_client import CoreApi
from turno_agents.llm import Completion, LlmClient, LlmError
from turno_agents.proposals import proposal_schema

_SIMULATOR_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {"message": {"type": "string"}, "done": {"type": "boolean"}},
    "required": ["message", "done"],
    "additionalProperties": False,
}

_SIMULATOR_PROMPT = """\
You play a patient writing to a dental clinic's chat, for a test. Write short, natural messages in \
{language}, one at a time, as a real person would. Never mention that this is a test.

Who you are: {persona}
What you want: {goal}

The conversation so far is below, where "clinic" is the other side. Write your next message.
- When the clinic asks you to reply with "sí" or "yes" to confirm something that suits your goal, \
reply with exactly that one word and nothing else.
- When it asks and it does not suit your goal, say so plainly, without using that word.
- Set done to true only when your goal has been achieved or the clinic has made clear that it cannot \
be, and then leave message empty. Never set it on your first message.
"""

_LANGUAGE_NAMES = {"es": "Spanish", "en": "English"}


class EvaluationAborted(Exception):
    """The run cannot continue, usually because the plan's limit was reached. Nothing was recorded."""


class CountingLlm:
    """Adds up what the calls of one role cost."""

    def __init__(self, inner: LlmClient) -> None:
        """Wrap a client.

        Args:
            inner: The client that does the work.
        """
        self._inner = inner
        self.calls = 0
        self.input_tokens = 0
        self.output_tokens = 0
        self.estimated_cost_usd = 0.0
        self.seconds: list[float] = []

    def complete(self, system: str, prompt: str, schema: dict[str, Any]) -> Completion:
        """Ask the model and record the cost.

        Args:
            system: The instructions.
            prompt: The conversation so far.
            schema: The schema of the answer.

        Returns:
            The answer.
        """
        completion = self._inner.complete(system, prompt, schema)
        self.calls += 1
        self.input_tokens += completion.input_tokens
        self.output_tokens += completion.output_tokens
        self.estimated_cost_usd += completion.estimated_cost_usd
        self.seconds.append(round(completion.seconds, 2))
        return completion


def _source_text(path: str) -> str:
    return Path(path).read_text(encoding="utf-8").replace("\r\n", "\n")


def scenario_fingerprint(scenario: Scenario, model: str) -> str:
    """Summarize everything that decides what the model receives in a scenario.

    A change to any of it makes the old recording say nothing about the new behaviour. The agent's
    code is left out on purpose: its rules are covered by unit tests, and re-recording the whole bank
    for each refactor is not affordable on this plan.

    Args:
        scenario: The scenario.
        model: The agent's model.

    Returns:
        A hash, which is stored in the recording.
    """
    parts = {
        "prompts": _source_text(str(prompts_module.__file__)),
        "schema": proposal_schema(),
        "model": model,
        "scenario": scenario.model_dump(mode="json"),
    }
    canonical = json.dumps(parts, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def clinic_facts(clinic: dict[str, Any]) -> ClinicFacts:
    """Extract from the clinic what replies can legitimately mention.

    Args:
        clinic: The clinic as the core describes it.

    Returns:
        The facts.
    """
    amounts: set[float] = set()
    for treatment in clinic["treatments"]:
        price = treatment["priceCents"] / 100
        deposit = treatment["depositCents"] / 100
        amounts |= {price, deposit, price - deposit}
    times: set[str] = set()
    for windows in clinic["openingHours"].values():
        for window in windows:
            times |= {window["open"][:5], window["close"][:5]}
    return ClinicFacts(
        amounts=sorted(amounts),
        practitioners=[practitioner["name"] for practitioner in clinic["practitioners"]],
        opening_times=sorted(times),
    )


def _state(appointment: dict[str, Any]) -> AppointmentState:
    return AppointmentState(
        id=appointment["id"],
        status=appointment["status"],
        treatment=appointment["treatmentId"],
        practitioner=appointment["practitionerId"],
        starts_at=appointment["startsAt"],
    )


def _seed(
    core: CoreApi, patient_id: str, treatments: list[str], run: str
) -> list[AppointmentState]:
    """Book each seed for real, far enough ahead to be inside the free cancellation period."""
    seeded = []
    first = date.today() + timedelta(days=7)
    last = first + timedelta(days=21)
    for index, treatment in enumerate(treatments):
        slots = core.availability(treatment, first.isoformat(), last.isoformat(), None)
        if not slots:
            raise EvaluationAborted(f"No free slot to seed {treatment}")
        # Different slots for different seeds, so that one patient's two seeds do not collide.
        slot = slots[index * 8 % len(slots)]
        held = core.hold(
            patient_id,
            slot["practitionerId"],
            slot["treatmentId"],
            slot["startsAt"],
            f"{run}-seed-hold-{index}",
        )
        seeded.append(_state(core.confirm(patient_id, held["id"], f"{run}-seed-confirm-{index}")))
    return seeded


def _next_simulated_message(
    simulator: LlmClient, scenario: Scenario, turns: list[Turn]
) -> str | None:
    system = _SIMULATOR_PROMPT.format(
        language=_LANGUAGE_NAMES[scenario.language],
        persona=scenario.persona,
        goal=scenario.goal,
    )
    lines = [f"{'you' if t.role == 'patient' else 'clinic'}: {t.text}" for t in turns]
    prompt = "\n".join(lines) if lines else "(The chat has just opened. Write your first message.)"
    output = simulator.complete(system, prompt, _SIMULATOR_SCHEMA).output
    message = str(output.get("message", "")).strip()
    if (output.get("done") and turns) or not message:
        return None
    return message


def run_scenario(
    scenario: Scenario,
    core: CoreApi,
    agent_llm: LlmClient,
    simulator_llm: LlmClient,
    model: str,
) -> Recording:
    """Run one scenario from start to end.

    Args:
        scenario: The scenario.
        core: The real core.
        agent_llm: The agent's access to the model.
        simulator_llm: The simulated patient's access to the model.
        model: The agent's model, as named in the fingerprint.

    Returns:
        What happened.

    Raises:
        EvaluationAborted: If the model failed, which must not count against the agent.
    """
    run = secrets.token_hex(8)
    agent_meter = CountingLlm(agent_llm)
    simulator_meter = CountingLlm(simulator_llm)
    agent = AgentService(agent_meter, core)
    patient = core.register_patient(
        "Evaluation Patient", f"+3469{secrets.randbelow(10**7):07d}", scenario.language
    )
    seed_before = _seed(core, patient["id"], scenario.seed, run)
    conversation = Conversation(id=run, patient_id=patient["id"], language=scenario.language)

    turns: list[Turn] = []
    script = list(scenario.script) if scenario.script is not None else None
    limit = len(script) if script is not None else scenario.max_turns
    try:
        for index in range(limit):
            if script is not None:
                message: str | None = script[index]
            else:
                message = _next_simulated_message(simulator_meter, scenario, turns)
            if message is None:
                break
            turns.append(Turn(role="patient", text=message))
            turns.append(Turn(role="agent", text=agent.handle(conversation, message)))
            if conversation.state is ConversationState.WITH_A_PERSON:
                break
    except LlmError as error:
        raise EvaluationAborted("The simulated patient could not answer") from error

    trace = trace_view(conversation)
    if any(step["kind"] == "handover" and step["detail"] == "model_failure" for step in trace):
        raise EvaluationAborted("The model failed, possibly because of the plan's limit")

    usage = Usage(
        agent_calls=agent_meter.calls,
        simulator_calls=simulator_meter.calls,
        input_tokens=agent_meter.input_tokens + simulator_meter.input_tokens,
        output_tokens=agent_meter.output_tokens + simulator_meter.output_tokens,
        estimated_cost_usd=round(
            agent_meter.estimated_cost_usd + simulator_meter.estimated_cost_usd, 6
        ),
        agent_call_seconds=agent_meter.seconds,
    )
    return Recording(
        scenario_id=scenario.id,
        fingerprint=scenario_fingerprint(scenario, model),
        model=model,
        language=scenario.language,
        turns=turns,
        core_results=_core_results(conversation),
        trace=trace,
        seed_before=seed_before,
        appointments_after=[_state(a) for a in core.appointments(patient["id"])],
        handed_over=conversation.state is ConversationState.WITH_A_PERSON,
        clinic=clinic_facts(core.clinic()),
        usage=usage,
    )


def _core_results(conversation: Conversation) -> list[str]:
    return [block for block in conversation.transcript if re.match(r"<core_result", block)]
