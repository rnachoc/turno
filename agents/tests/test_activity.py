"""Tests of the background report of what the agents did.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import json
import threading
from collections.abc import Sequence

import httpx2
import pytest

from tests.fakes import OTHER_PATIENT, PATIENT, FakeCore
from turno_agents.activity import MAX_STEPS_PER_REPORT, ActivityReporter
from turno_agents.conversation import ConversationStore, TraceStep
from turno_agents.core_client import CoreRefusalError, HttpCoreClient


def _store_with_steps(count: int, patient: str = PATIENT) -> tuple[ConversationStore, str]:
    store = ConversationStore()
    conversation = store.create(patient, "es")
    conversation.trace.extend(TraceStep("action", f"step-{i}") for i in range(count))
    return store, conversation.id


def test_steps_reach_the_core_once() -> None:
    """A step is reported once, however many rounds run."""
    core = FakeCore()
    store, conversation_id = _store_with_steps(2)
    reporter = ActivityReporter(store, core)

    reporter.report_pending()
    reporter.report_pending()

    assert core.reported == [(PATIENT, conversation_id, 0, ["step-0", "step-1"])]


def test_only_new_steps_are_sent() -> None:
    """Later rounds send what came after the last report."""
    core = FakeCore()
    store, conversation_id = _store_with_steps(1)
    reporter = ActivityReporter(store, core)
    reporter.report_pending()

    conversation = store.get(conversation_id)
    assert conversation is not None
    conversation.trace.append(TraceStep("handover", "core_down"))
    reporter.report_pending()

    assert core.reported[-1] == (PATIENT, conversation_id, 1, ["core_down"])


def test_long_conversations_go_in_pieces_the_core_accepts() -> None:
    """A long trace is split below the core's limit."""
    core = FakeCore()
    store, conversation_id = _store_with_steps(MAX_STEPS_PER_REPORT + 5)

    ActivityReporter(store, core).report_pending()

    assert [(first, len(steps)) for _, _, first, steps in core.reported] == [
        (0, MAX_STEPS_PER_REPORT),
        (MAX_STEPS_PER_REPORT, 5),
    ]
    assert all(reported[1] == conversation_id for reported in core.reported)


def test_a_handover_during_an_outage_arrives_when_the_core_returns() -> None:
    """Nothing is lost while the core is down."""
    core = FakeCore()
    core.down = True
    store = ConversationStore()
    conversation = store.create(PATIENT, "es")
    conversation.trace.append(TraceStep("handover", "core_down"))
    reporter = ActivityReporter(store, core)

    reporter.report_pending()
    assert core.reported == []

    core.down = False
    reporter.report_pending()

    assert core.reported == [(PATIENT, conversation.id, 0, ["core_down"])]


def test_an_outage_stops_the_round_instead_of_waiting_once_per_conversation() -> None:
    """One failure ends the round, so an outage costs one timeout."""
    core = FakeCore()
    core.down = True
    store = ConversationStore()
    for _ in range(3):
        store.create(PATIENT, "es").trace.append(TraceStep("action", "x"))

    ActivityReporter(store, core).report_pending()

    assert core.report_attempts == 1


def test_a_refusal_does_not_block_the_rest(caplog: pytest.LogCaptureFixture) -> None:
    """A refused report is logged, skipped and not sent again."""
    core = FakeCore()
    store = ConversationStore()
    refused = store.create(OTHER_PATIENT, "es")
    refused.trace.append(TraceStep("action", "x"))
    accepted = store.create(PATIENT, "es")
    accepted.trace.append(TraceStep("action", "y"))
    core.refuse_reports_for = OTHER_PATIENT
    reporter = ActivityReporter(store, core)

    reporter.report_pending()
    reporter.report_pending()

    assert core.report_attempts == 2
    assert core.reported == [(PATIENT, accepted.id, 0, ["y"])]
    assert "VALIDATION_FAILED" in caplog.text


def test_it_sends_in_the_background_and_flushes_when_stopping() -> None:
    """The thread reports by itself and once more when stopping."""
    core = FakeCore()
    store, conversation_id = _store_with_steps(1)
    reporter = ActivityReporter(store, core, interval=0.01)

    reporter.start()
    deadline = threading.Event()
    for _ in range(500):
        if core.reported:
            break
        deadline.wait(0.01)
    assert core.reported == [(PATIENT, conversation_id, 0, ["step-0"])]

    conversation = store.get(conversation_id)
    assert conversation is not None
    conversation.trace.append(TraceStep("action", "last"))
    reporter.stop()

    assert core.reported[-1] == (PATIENT, conversation_id, 1, ["last"])


def test_stopping_flushes_even_if_the_interval_is_long() -> None:
    """Stopping does not wait for the next round."""
    core = FakeCore()
    store, _ = _store_with_steps(1)
    reporter = ActivityReporter(store, core, interval=3600)

    reporter.start()
    reporter.stop()

    assert len(core.reported) == 1


def _client_that_records(requests: list[httpx2.Request], status: int = 200) -> HttpCoreClient:
    def handler(request: httpx2.Request) -> httpx2.Response:
        requests.append(request)
        body = {"added": 1} if status == 200 else {"code": "VALIDATION_FAILED"}
        return httpx2.Response(status, json=body)

    return HttpCoreClient("http://core", "token", httpx2.MockTransport(handler))


def _sample_steps() -> Sequence[TraceStep]:
    return [TraceStep("model", "claude-opus-5-5", 10, 5, 0.0125, 1.5), TraceStep("handover", "x")]


def test_the_request_has_the_shape_the_core_expects() -> None:
    """The body matches what the core validates."""
    requests: list[httpx2.Request] = []
    client = _client_that_records(requests)

    client.record_steps(PATIENT, "conversation-id", 3, _sample_steps())

    request = requests[0]
    assert request.method == "POST"
    assert request.url.path == f"/api/v1/patients/{PATIENT}/conversations/conversation-id/steps"
    assert json.loads(request.content) == {
        "firstPosition": 3,
        "steps": [
            {
                "kind": "model",
                "detail": "claude-opus-5-5",
                "inputTokens": 10,
                "outputTokens": 5,
                "estimatedCostUsd": 0.0125,
                "seconds": 1.5,
            },
            {
                "kind": "handover",
                "detail": "x",
                "inputTokens": 0,
                "outputTokens": 0,
                "estimatedCostUsd": 0.0,
                "seconds": 0.0,
            },
        ],
    }


def test_a_retry_of_the_same_steps_reuses_its_key_and_a_longer_one_does_not() -> None:
    """The key depends on exactly which steps are sent."""
    requests: list[httpx2.Request] = []
    client = _client_that_records(requests)
    steps = _sample_steps()

    client.record_steps(PATIENT, "c", 0, steps)
    client.record_steps(PATIENT, "c", 0, steps)
    client.record_steps(PATIENT, "c", 0, [*steps, TraceStep("action", "more")])

    keys = [request.headers["Idempotency-Key"] for request in requests]
    assert keys[0] == keys[1]
    assert keys[0] != keys[2]


def test_a_refusal_comes_with_its_code() -> None:
    """The core's code reaches the caller."""
    client = _client_that_records([], status=400)

    with pytest.raises(CoreRefusalError) as error:
        client.record_steps(PATIENT, "c", 0, _sample_steps())

    assert error.value.code == "VALIDATION_FAILED"
