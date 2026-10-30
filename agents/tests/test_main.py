"""Tests for the HTTP interface of the agents service.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import pytest
from fastapi.testclient import TestClient

from tests.fakes import FakeCore
from turno_agents.conversation import ConversationStore
from turno_agents.llm import ScriptedLlm
from turno_agents.main import create_app
from turno_agents.settings import Settings

PHONE = "+34600000001"


def client_with(llm: ScriptedLlm | None = None, core: FakeCore | None = None) -> TestClient:
    """Build a client for an application with the given collaborators."""
    settings = Settings(llm_backend="none", api_token_agents=None)
    return TestClient(create_app(settings, llm, core), base_url="http://127.0.0.1")


@pytest.fixture
def client() -> TestClient:
    """Provide a client for an application with a scripted model and a fake core."""
    llm = ScriptedLlm([{"reply": "Hola", "action": {"type": "none"}}])
    return client_with(llm, FakeCore())


def test_health_reports_up(client: TestClient) -> None:
    """The health endpoint answers 200 with the status and nothing else."""
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


@pytest.mark.parametrize("path", ["/docs", "/redoc", "/openapi.json"])
def test_api_description_is_not_exposed(client: TestClient, path: str) -> None:
    """The docs and the schema must not reveal the service surface."""
    assert client.get(path).status_code == 404


@pytest.mark.parametrize("method", ["post", "put", "patch", "delete"])
def test_health_is_read_only(client: TestClient, method: str) -> None:
    """The health endpoint rejects every method that could change state."""
    assert getattr(client, method)("/health").status_code == 405


def test_a_conversation_goes_from_start_to_reply(client: TestClient) -> None:
    """The patient's identifier is not returned, because no caller needs it."""
    started = client.post("/api/conversations", json={"phone": PHONE})

    assert started.status_code == 201
    assert set(started.json()) == {"conversation_id", "language"}

    conversation = started.json()["conversation_id"]
    answer = client.post(f"/api/conversations/{conversation}/messages", json={"text": "hola"})

    assert answer.status_code == 200
    assert answer.json() == {"reply": "Hola", "state": "active"}


def test_an_unknown_phone_without_a_name_is_not_registered(client: TestClient) -> None:
    """The page then asks for a name, and nobody gets registered by accident."""
    response = client.post("/api/conversations", json={"phone": "+34600000999"})

    assert response.status_code == 404
    assert response.json()["detail"]["code"] == "PATIENT_NOT_FOUND"


def test_an_unknown_phone_with_a_name_is_registered() -> None:
    """New patients can start a conversation."""
    core = FakeCore()
    client = client_with(ScriptedLlm(), core)

    response = client.post(
        "/api/conversations",
        json={"phone": "+34600000999", "full_name": "New Person", "language": "en"},
    )

    assert response.status_code == 201
    assert response.json()["language"] == "en"
    assert core.writes == [("register", "+34600000999", None)]


@pytest.mark.parametrize(
    "body",
    [
        {},
        {"phone": "600000001"},
        {"phone": "+34 600 000 001"},
        {"phone": PHONE, "patient_id": "someone-else"},
        {"phone": PHONE, "full_name": ""},
        {"phone": PHONE, "language": "spanish"},
    ],
)
def test_invalid_starts_are_refused(client: TestClient, body: dict[str, str]) -> None:
    """In particular, nobody can choose the patient by identifier."""
    assert client.post("/api/conversations", json=body).status_code == 422


def test_a_conversation_must_exist(client: TestClient) -> None:
    """Identifiers are unguessable, and an unknown one reveals nothing."""
    response = client.post("/api/conversations/nope/messages", json={"text": "hola"})

    assert response.status_code == 404
    assert response.json()["detail"]["code"] == "CONVERSATION_NOT_FOUND"


@pytest.mark.parametrize(
    "body", [{}, {"text": ""}, {"text": "x" * 1001}, {"text": "hola", "state": "active"}]
)
def test_invalid_messages_are_refused(client: TestClient, body: dict[str, str]) -> None:
    """Length is limited, which bounds what a single message can cost."""
    conversation = client.post("/api/conversations", json={"phone": PHONE}).json()[
        "conversation_id"
    ]

    response = client.post(f"/api/conversations/{conversation}/messages", json=body)

    assert response.status_code == 422


def test_without_a_model_conversations_are_unavailable() -> None:
    """The container has no Claude login, and says so clearly."""
    response = client_with(None, FakeCore()).post("/api/conversations", json={"phone": PHONE})

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "MODEL_NOT_CONFIGURED"


def test_a_core_outage_is_reported_when_starting() -> None:
    """Nothing can start without the core."""
    core = FakeCore()
    core.down = True

    response = client_with(ScriptedLlm(), core).post("/api/conversations", json={"phone": PHONE})

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "CORE_UNAVAILABLE"


def test_a_conversation_handles_one_message_at_a_time() -> None:
    """Two parallel messages must not interleave their steps or spend the plan twice."""
    store = ConversationStore()
    settings = Settings()
    app = create_app(settings, ScriptedLlm(), FakeCore(), store)
    client = TestClient(app, base_url="http://127.0.0.1")
    conversation = client.post("/api/conversations", json={"phone": PHONE}).json()[
        "conversation_id"
    ]
    found = store.get(conversation)
    assert found is not None

    # Taken directly, as a stand-in for a message that is still being processed.
    with found.lock:
        response = client.post(f"/api/conversations/{conversation}/messages", json={"text": "hola"})

    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "CONVERSATION_BUSY"


def test_the_trace_shows_steps_without_patient_text(client: TestClient) -> None:
    """It can be inspected without exposing what the patient wrote."""
    conversation = client.post("/api/conversations", json={"phone": PHONE}).json()[
        "conversation_id"
    ]
    client.post(f"/api/conversations/{conversation}/messages", json={"text": "me llamo Secretname"})

    trace = client.get(f"/api/conversations/{conversation}/trace")

    assert trace.status_code == 200
    assert trace.json()["steps"][0]["kind"] == "model"
    assert "Secretname" not in trace.text


def test_other_host_names_are_refused() -> None:
    """A malicious website cannot reach this service through DNS rebinding."""
    settings = Settings()
    client = TestClient(create_app(settings), base_url="http://attacker.example")

    assert client.get("/health").status_code == 400


def test_the_chat_page_is_served_with_strict_headers(client: TestClient) -> None:
    """The page can only load its own files, and it cannot be framed."""
    page = client.get("/")

    assert page.status_code == 200
    assert "Synthetic data only" in page.text
    policy = page.headers["content-security-policy"]
    assert "default-src 'self'" in policy
    assert "frame-ancestors 'none'" in policy
    assert page.headers["x-content-type-options"] == "nosniff"
    assert client.get("/static/chat.js").status_code == 200
    assert client.get("/static/chat.css").status_code == 200


def test_the_page_never_builds_markup_from_messages(client: TestClient) -> None:
    """Replies are written as text, so a reply cannot inject anything into the page."""
    script = client.get("/static/chat.js").text

    assert "textContent" in script
    assert "innerHTML" not in script
    assert "insertAdjacentHTML" not in script
