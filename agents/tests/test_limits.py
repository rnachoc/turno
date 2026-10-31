"""Tests of the limits on the agents.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import threading
from typing import Any

import pytest
from fastapi.testclient import TestClient

from tests.fakes import CLINIC, FakeCore
from turno_agents.conversation import ConversationState, ConversationStore
from turno_agents.limits import WindowLimiter
from turno_agents.llm import ScriptedLlm
from turno_agents.main import create_app
from turno_agents.settings import Settings

PHONE = "+34600111222"


def _say(text: str) -> dict[str, Any]:
    return {"reply": text, "action": {"type": "none"}}


class Clock:
    """A clock that only moves by hand."""

    def __init__(self) -> None:
        """Start at the beginning of a window."""
        self.value = 6000.0

    def __call__(self) -> float:
        """Read the clock.

        Returns:
            The time, in seconds.
        """
        return self.value


def test_events_within_the_limit_pass_and_the_rest_do_not() -> None:
    """The limit is exact."""
    limiter = WindowLimiter(2, 60, Clock())

    assert [limiter.allow("a") for _ in range(4)] == [True, True, False, False]


def test_each_key_has_its_own_count() -> None:
    """One patient cannot use up another's limit."""
    limiter = WindowLimiter(1, 60, Clock())

    assert limiter.allow("a")
    assert not limiter.allow("a")
    assert limiter.allow("b")


def test_the_next_window_starts_again() -> None:
    """Waiting is enough."""
    clock = Clock()
    limiter = WindowLimiter(1, 60, clock)
    limiter.allow("a")
    assert not limiter.allow("a")

    clock.value += 60

    assert limiter.allow("a")


def test_it_says_how_long_is_left() -> None:
    """The answer can be passed on to the caller."""
    clock = Clock()
    limiter = WindowLimiter(1, 60, clock)

    assert limiter.seconds_until_next_window() == 60
    clock.value += 45.5
    assert limiter.seconds_until_next_window() == 15


def test_made_up_keys_cannot_fill_the_memory() -> None:
    """The count of keys stays bounded, and the limit still holds for the newest."""
    limiter = WindowLimiter(1, 60, Clock())

    for i in range(25_000):
        limiter.allow(f"key-{i}")

    assert len(limiter._counts) <= 10_000
    assert limiter.allow("fresh")
    assert not limiter.allow("fresh")


def test_threads_cannot_slip_past_the_limit() -> None:
    """Counting is atomic."""
    limiter = WindowLimiter(100, 60, Clock())
    allowed: list[bool] = []

    def hammer() -> None:
        for _ in range(100):
            allowed.append(limiter.allow("a"))

    threads = [threading.Thread(target=hammer) for _ in range(8)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert sum(allowed) == 100


def _client(
    llm: ScriptedLlm | None = None, core: FakeCore | None = None, **limits: int
) -> tuple[TestClient, ConversationStore]:
    settings = Settings(_env_file=None, allowed_hosts=["127.0.0.1"], **limits)  # type: ignore[call-arg, arg-type]
    store = ConversationStore()
    app = create_app(settings, llm or ScriptedLlm([]), core or FakeCore(), store)
    return TestClient(app, base_url="http://127.0.0.1"), store


def _start(client: TestClient, phone: str = PHONE) -> str:
    response = client.post(
        "/api/conversations", json={"phone": phone, "full_name": "Laura Test", "language": "es"}
    )
    assert response.status_code == 201, response.text
    return str(response.json()["conversation_id"])


def _send(client: TestClient, conversation: str, text: str) -> Any:
    return client.post(f"/api/conversations/{conversation}/messages", json={"text": text})


def test_too_many_messages_are_told_to_wait_without_reaching_the_model() -> None:
    """Over the limit nothing is spent."""
    llm = ScriptedLlm([_say("uno"), _say("dos"), _say("tres")])
    client, _ = _client(llm, max_messages_per_conversation_per_minute=2)
    conversation = _start(client)

    assert _send(client, conversation, "hola").status_code == 200
    assert _send(client, conversation, "hola").status_code == 200
    refused = _send(client, conversation, "hola")

    assert refused.status_code == 429
    assert refused.json()["detail"]["code"] == "TOO_MANY_MESSAGES"
    assert 1 <= int(refused.headers["Retry-After"]) <= 60
    assert len(llm.outputs) == 1


@pytest.mark.parametrize("emergency", ["no puedo respirar", "I can't breathe", "me muero"])
def test_an_emergency_is_answered_even_over_the_limit(emergency: str) -> None:
    """A limit must never stand between someone and the emergency number."""
    client, _ = _client(ScriptedLlm([_say("uno")]), max_messages_per_conversation_per_minute=1)
    conversation = _start(client)
    _send(client, conversation, "hola")
    assert _send(client, conversation, "hola").status_code == 429

    response = _send(client, conversation, emergency)

    assert response.status_code == 200
    assert CLINIC["emergencyNumber"] in response.json()["reply"]
    assert response.json()["state"] == ConversationState.WITH_A_PERSON.value


def test_one_conversation_cannot_use_up_the_limit_of_another() -> None:
    """Limits are per conversation."""
    client, _ = _client(
        ScriptedLlm([_say("uno"), _say("dos")]), max_messages_per_conversation_per_minute=1
    )
    first = _start(client, "+34600111222")
    second = _start(client, "+34600111333")

    assert _send(client, first, "hola").status_code == 200
    assert _send(client, first, "hola").status_code == 429
    assert _send(client, second, "hola").status_code == 200


def test_an_emergency_gets_through_while_the_model_is_busy() -> None:
    """A message in progress must not leave someone without the number."""
    client, store = _client(ScriptedLlm([]))
    conversation = _start(client)
    held = store.get(conversation)
    assert held is not None

    with held.lock:
        response = _send(client, conversation, "no puedo respirar")

    assert response.status_code == 200
    assert CLINIC["emergencyNumber"] in response.json()["reply"]
    assert response.json()["state"] == ConversationState.WITH_A_PERSON.value
    assert [step.kind for step in held.trace] == ["handover"]


def test_anything_else_still_waits_while_the_model_is_busy() -> None:
    """Two turns at once would mix up a conversation."""
    client, store = _client(ScriptedLlm([]))
    conversation = _start(client)
    held = store.get(conversation)
    assert held is not None

    with held.lock:
        response = _send(client, conversation, "hola")

    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "CONVERSATION_BUSY"


def test_starting_many_conversations_for_one_phone_is_refused() -> None:
    """Otherwise the limit per conversation would mean nothing."""
    client, _ = _client(max_conversations_per_phone_per_hour=2)
    _start(client)
    _start(client)

    refused = client.post("/api/conversations", json={"phone": PHONE, "full_name": "Laura Test"})

    assert refused.status_code == 429
    assert refused.json()["detail"]["code"] == "TOO_MANY_CONVERSATIONS"
    assert int(refused.headers["Retry-After"]) >= 1
    _start(client, "+34600111333")


def test_starting_many_conversations_with_made_up_phones_is_refused() -> None:
    """Each one would register a patient in the core."""
    core = FakeCore()
    client, _ = _client(core=core, max_conversations_per_minute=2)
    _start(client, "+34600000001")
    _start(client, "+34600000002")
    before = len(core.writes)

    refused = client.post(
        "/api/conversations", json={"phone": "+34600000003", "full_name": "Laura Test"}
    )

    assert refused.status_code == 429
    assert len(core.writes) == before


@pytest.mark.parametrize(
    "limit",
    [
        "max_messages_per_conversation_per_minute",
        "max_conversations_per_phone_per_hour",
        "max_conversations_per_minute",
    ],
)
def test_a_limit_of_zero_is_refused_at_startup(limit: str) -> None:
    """It would stop every conversation, which must not pass for a setting."""
    with pytest.raises(ValueError, match=limit):
        Settings(_env_file=None, **{limit: 0})  # type: ignore[call-arg, arg-type]
