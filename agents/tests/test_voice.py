"""Tests of the voice channel, with engines that follow a script.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import json
import threading
from collections.abc import Iterator
from typing import Any

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from tests.fakes import CLINIC, PATIENT, FakeCore
from turno_agents.agent import AgentService
from turno_agents.conversation import Conversation, ConversationState, ConversationStore
from turno_agents.llm import ScriptedLlm, ScriptedOutput
from turno_agents.main import create_app
from turno_agents.settings import Settings
from turno_agents.voice.engines import FRAME_BYTES, Transcript, VoiceEngines
from turno_agents.voice.routes import MAX_CALLS, MAX_FRAME_BYTES, is_same_origin
from turno_agents.voice.session import (
    MIN_CONFIDENCE,
    MIN_CONFIDENCE_TO_AGREE,
    VoiceSession,
    speakable,
)

SPEECH = b"\x7f" * FRAME_BYTES
SILENCE = b"\x00" * FRAME_BYTES
TTS_RATE = 1000
SURE = 0.9


def _say(text: str) -> dict[str, Any]:
    return {"reply": text, "action": {"type": "none"}}


class Clock:
    """A clock that only moves by hand."""

    def __init__(self) -> None:
        """Start somewhere."""
        self.value = 100.0

    def __call__(self) -> float:
        """Read the clock.

        Returns:
            The time.
        """
        return self.value


class FakeDetector:
    """Says that loud frames hold speech."""

    def probability(self, frame: bytes) -> float:
        """Read a frame.

        Args:
            frame: The frame.

        Returns:
            One if it is loud.
        """
        return 1.0 if frame[0] else 0.0


class FakeStt:
    """Hears what it is told to."""

    def __init__(self) -> None:
        """Start with nothing to hear."""
        self.queue: list[Transcript] = []
        self.heard: list[int] = []
        self.gate: threading.Event | None = None

    def transcribe(self, samples: bytes, language: str) -> Transcript:
        """Give the next transcript.

        Args:
            samples: The audio.
            language: The language.

        Returns:
            The transcript.
        """
        self.heard.append(len(samples))
        return self.queue.pop(0)


class FakeTts:
    """Says everything in a second of audio per ten characters."""

    sample_rate = TTS_RATE

    def __init__(self) -> None:
        """Remember what it said."""
        self.said: list[str] = []

    def synthesize(self, text: str, language: str) -> bytes:
        """Say something.

        Args:
            text: The words.
            language: The language.

        Returns:
            The audio.
        """
        self.said.append(text)
        return b"\x01\x00" * (TTS_RATE * max(1, len(text) // 10))


class Sink:
    """Keeps what a session sends."""

    def __init__(self) -> None:
        """Start empty."""
        self.events: list[dict[str, Any]] = []
        self.audio_sent: list[bytes] = []

    def event(self, payload: dict[str, object]) -> None:
        """Keep a message.

        Args:
            payload: The message.
        """
        self.events.append(payload)

    def audio(self, samples: bytes) -> None:
        """Keep some audio.

        Args:
            samples: The audio.
        """
        self.audio_sent.append(samples)

    def of(self, kind: str) -> list[dict[str, Any]]:
        """Filter the messages.

        Args:
            kind: Their type.

        Returns:
            The messages of that type.
        """
        return [event for event in self.events if event["type"] == kind]


class Call:
    """A session with everything around it under control."""

    def __init__(self, outputs: list[ScriptedOutput] | None = None, allow: bool = True) -> None:
        """Start the call.

        Args:
            outputs: What the model answers.
            allow: Whether the limit of messages allows more.
        """
        self.core = FakeCore()
        self.llm = ScriptedLlm(outputs or [])
        self.agent = AgentService(self.llm, self.core)
        self.conversation = Conversation(id="c", patient_id=PATIENT, language="es")
        self.stt = FakeStt()
        self.tts = FakeTts()
        self.sink = Sink()
        self.clock = Clock()
        engines = VoiceEngines(self.stt, self.tts, FakeDetector)
        self.session = VoiceSession(
            self.agent, self.conversation, engines, self.sink, lambda: allow, self.clock
        )

    def frames(self, frame: bytes, count: int) -> None:
        """Send frames, moving the clock as they would take.

        Args:
            frame: The frame.
            count: How many.
        """
        for _ in range(count):
            self.session.feed(frame)
            self.clock.value += 0.032

    def utter(self, text: str, confidence: float = SURE) -> None:
        """Have the patient say something, and wait for what follows.

        Args:
            text: What is heard.
            confidence: How sure the engine is.
        """
        self.stt.queue.append(Transcript(text, confidence))
        self.frames(SPEECH, 10)
        self.frames(SILENCE, 25)
        self.session.drain()

    def wait_for_the_end_of_playback(self) -> None:
        """Let everything said be heard."""
        self.clock.value += 1000


@pytest.fixture
def call() -> Iterator[Call]:
    """Provide a call with a model that says hello."""
    built = Call([_say("Hola")])
    yield built
    built.session.close()


def test_silence_is_not_a_turn(call: Call) -> None:
    """Nothing is transcribed, which would spend time on nothing."""
    call.frames(SILENCE, 200)
    call.session.drain()

    assert call.stt.heard == []
    assert call.sink.events == []


def test_a_cough_is_not_a_turn(call: Call) -> None:
    """Two loud frames do not make someone speaking."""
    call.frames(SPEECH, 2)
    call.frames(SILENCE, 50)
    call.session.drain()

    assert call.stt.heard == []


def test_pieces_of_any_size_make_up_the_same_frames(call: Call) -> None:
    """The browser sends what it has, not whole frames."""
    call.stt.queue.append(Transcript("hola", SURE))
    audio = SPEECH * 10 + SILENCE * 25

    for start in range(0, len(audio), 700):
        call.session.feed(audio[start : start + 700])
    call.session.drain()

    assert len(call.stt.heard) == 1


def test_the_start_of_a_word_is_not_clipped(call: Call) -> None:
    """What came just before the detector was sure goes along."""
    call.frames(SILENCE, 20)
    call.utter("hola")

    # At least ten frames of speech, the silence that ended it, and some from before.
    assert call.stt.heard[0] > (10 + 21) * FRAME_BYTES


def test_a_turn_goes_through_the_agent(call: Call) -> None:
    """Heard, a holding phrase at once, and then the reply."""
    call.utter("quiero una cita")

    assert call.sink.of("heard") == [{"type": "heard", "text": "quiero una cita"}]
    assert call.tts.said == ["Un momento.", "Hola"]
    assert len(call.sink.audio_sent) == 2
    assert "quiero una cita" in call.llm.prompts[0]


def test_it_says_how_long_each_part_took(call: Call) -> None:
    """Which is what the report publishes."""
    call.utter("hola")

    timing = call.sink.of("timing")[0]
    assert timing["hear_seconds"] == pytest.approx(0, abs=0.01)
    assert timing["first_audio_seconds"] is not None
    assert timing["reply_seconds"] is not None


def test_nothing_heard_is_not_a_turn(call: Call) -> None:
    """Whisper hears nothing in many noises."""
    call.utter("   ")

    assert call.tts.said == []
    assert call.llm.prompts == []


def test_a_long_speech_is_cut_at_the_limit(call: Call) -> None:
    """It cannot fill the memory, however long it goes on."""
    call.stt.queue.append(Transcript("hola", SURE))

    call.frames(SPEECH, int(21 / 0.032))
    call.session.drain()

    assert len(call.stt.heard) == 1
    assert call.stt.heard[0] <= 21 * 16000 * 2


def test_something_unclear_is_asked_again_without_the_model(call: Call) -> None:
    """A guess would be worse than asking."""
    call.utter("cancela la cita", confidence=MIN_CONFIDENCE - 0.01)

    assert "no te he entendido" in call.tts.said[0]
    assert call.llm.prompts == []


@pytest.mark.parametrize("text", ["no puedo respirar", "me duele el pecho"])
def test_an_emergency_gets_the_number_without_the_model_and_at_once(call: Call, text: str) -> None:
    """With no holding phrase, and passing to a person."""
    call.agent.warm_up()

    call.utter(text)

    assert "112" in call.tts.said[0]
    assert "momento" not in call.tts.said[0]
    assert call.llm.prompts == []
    assert call.conversation.state is ConversationState.WITH_A_PERSON


def test_an_emergency_counts_however_unsure_the_engine_is(call: Call) -> None:
    """Mistaking something for an emergency costs little, and the opposite does not."""
    call.agent.warm_up()

    call.utter("no puedo respirar", confidence=0.05)

    assert "112" in call.tts.said[0]


def test_an_emergency_gets_through_while_the_model_is_thinking() -> None:
    """A turn in progress must not leave someone waiting for the number."""
    release = threading.Event()
    started = threading.Event()

    def slow() -> dict[str, Any]:
        started.set()
        release.wait(5)
        return _say("Hola")

    call = Call([slow])
    call.agent.warm_up()
    call.stt.queue.append(Transcript("quiero una cita", SURE))
    call.frames(SPEECH, 10)
    call.frames(SILENCE, 25)
    assert started.wait(5)

    call.stt.queue.append(Transcript("no puedo respirar", SURE))
    call.frames(SPEECH, 10)
    call.frames(SILENCE, 25)
    call.session._hearing.submit(lambda: None).result()

    assert any("112" in text for text in call.tts.said)
    release.set()
    call.session.drain()
    # The reply to the older message is no longer worth saying.
    assert "Hola" not in call.tts.said
    call.session.close()


def test_talking_over_a_reply_stops_it() -> None:
    """The page is told to stop at once."""
    call = Call([_say("Una respuesta muy larga para que tarde en leerse"), _say("Dos")])
    call.utter("hola")
    assert call.sink.of("stop") == []

    call.utter("espera")

    assert len(call.sink.of("stop")) == 1
    call.session.close()


def test_talking_after_a_reply_has_ended_stops_nothing() -> None:
    """Nothing is playing by then."""
    call = Call([_say("Hola"), _say("Dos")])
    call.utter("hola")
    call.wait_for_the_end_of_playback()

    call.utter("otra cosa")

    assert call.sink.of("stop") == []
    call.session.close()


def _call_with_a_question() -> tuple[Call, str]:
    call = Call()
    appointment = call.core.add(PATIENT, status="HOLD")
    call.llm.outputs = [
        {"reply": "...", "action": {"type": "confirm_appointment", "appointment_id": appointment}},
        _say("Tu cita queda confirmada."),
    ]
    call.utter("confirma mi cita")
    assert call.conversation.pending is not None
    return call, appointment


def test_a_clear_yes_to_a_question_heard_to_the_end_confirms() -> None:
    """The ordinary case, by the path of any consent."""
    call, appointment = _call_with_a_question()
    call.wait_for_the_end_of_playback()

    call.utter("sí")

    assert call.core.appointments_by_id[appointment]["status"] == "CONFIRMED"
    call.session.close()


def test_a_yes_the_engine_is_not_sure_of_confirms_nothing() -> None:
    """It could have been anything, so the question is asked again in the code's words."""
    call, appointment = _call_with_a_question()
    call.wait_for_the_end_of_playback()
    pending = call.conversation.pending
    assert pending is not None
    prompts = len(call.llm.prompts)

    call.utter("sí", confidence=MIN_CONFIDENCE_TO_AGREE - 0.01)

    assert call.core.appointments_by_id[appointment]["status"] == "HOLD"
    assert pending.description in call.tts.said[-1]
    assert call.conversation.pending is pending
    assert len(call.llm.prompts) == prompts
    call.session.close()


def test_a_yes_said_over_the_question_confirms_nothing() -> None:
    """Someone who talks over it may not have heard what they would be agreeing to."""
    call, appointment = _call_with_a_question()
    pending = call.conversation.pending
    assert pending is not None
    assert call.clock.value < call.session._playing_until

    call.utter("sí")

    assert call.core.appointments_by_id[appointment]["status"] == "HOLD"
    assert pending.description in call.tts.said[-1]
    assert len(call.sink.of("stop")) == 1
    call.session.close()


def test_a_yes_to_the_question_read_again_confirms() -> None:
    """Once heard to the end, it counts."""
    call, appointment = _call_with_a_question()
    call.utter("sí")
    assert call.core.appointments_by_id[appointment]["status"] == "HOLD"
    call.wait_for_the_end_of_playback()

    call.utter("sí")

    assert call.core.appointments_by_id[appointment]["status"] == "CONFIRMED"
    call.session.close()


def test_a_reply_that_was_never_played_cannot_be_agreed_to() -> None:
    """The question was thrown away, so a yes cannot answer it."""
    release = threading.Event()
    started = threading.Event()
    call = Call()
    appointment = call.core.add(PATIENT, status="HOLD")

    def slow() -> dict[str, Any]:
        started.set()
        release.wait(5)
        return {
            "reply": "...",
            "action": {"type": "confirm_appointment", "appointment_id": appointment},
        }

    call.llm.outputs = [slow]
    call.stt.queue.append(Transcript("confirma mi cita", SURE))
    call.frames(SPEECH, 10)
    call.frames(SILENCE, 25)
    assert started.wait(5)
    # The holding phrase is playing, and they talk over it while the model thinks.
    call.frames(SPEECH, 10)
    call.stt.queue.append(Transcript("sí", SURE))
    call.frames(SILENCE, 25)
    release.set()
    call.session.drain()
    call.session.drain()

    assert call.core.appointments_by_id[appointment]["status"] == "HOLD"
    call.session.close()


def test_a_caller_over_the_limit_spends_nothing() -> None:
    """Each message would spend a share of the plan."""
    call = Call([_say("Hola")], allow=False)

    call.utter("hola")

    assert call.llm.prompts == []
    assert call.tts.said == []
    call.session.close()


def test_an_emergency_gets_through_over_the_limit() -> None:
    """No limit stands before the number."""
    call = Call(allow=False)
    call.agent.warm_up()

    call.utter("no puedo respirar")

    assert "112" in call.tts.said[0]
    call.session.close()


def test_a_failure_in_a_turn_is_not_silence() -> None:
    """Someone on a call must not be left wondering."""
    call = Call()
    call.stt.queue.append(Transcript("hola", SURE))

    def broken(*_: object) -> Transcript:
        raise RuntimeError("the engine broke")

    call.stt.transcribe = broken  # type: ignore[method-assign,assignment]
    call.frames(SPEECH, 10)
    call.frames(SILENCE, 25)
    call.session.drain()

    assert "no te he entendido" in call.tts.said[0]
    call.session.close()


def test_what_is_read_aloud_has_no_marks_or_symbols() -> None:
    """A listener should not hear asterisks."""
    text = "**Hola**. Horarios:\n- Lunes a las 10:00\n* Martes\nCuesta 35 €."

    spoken = speakable(text)

    assert spoken == "Hola. Horarios: Lunes a las 10:00 Martes Cuesta 35 euros."


@pytest.mark.parametrize(
    ("origin", "host", "allowed"),
    [
        ("http://127.0.0.1:8001", "127.0.0.1:8001", True),
        ("https://clinic.example", "clinic.example", True),
        ("http://evil.example", "127.0.0.1:8001", False),
        ("http://127.0.0.1:9999", "127.0.0.1:8001", False),
        ("http://127.0.0.1:8001.evil.example", "127.0.0.1:8001", False),
        ("file://", "127.0.0.1:8001", False),
        ("null", "127.0.0.1:8001", False),
        (None, "127.0.0.1:8001", False),
        ("", "127.0.0.1:8001", False),
        ("http://127.0.0.1:8001", None, False),
    ],
)
def test_only_a_page_of_this_service_can_open_a_call(
    origin: str | None, host: str | None, allowed: bool
) -> None:
    """Any web page can open a socket to any address, and the browser would allow it."""
    assert is_same_origin(origin, host) is allowed


def _app(voice: bool = True, **limits: int) -> TestClient:
    settings = Settings(
        _env_file=None,  # type: ignore[call-arg]
        allowed_hosts=["127.0.0.1", "testserver"],
        voice_enabled=voice,
        **limits,  # type: ignore[arg-type]
    )
    engines = VoiceEngines(FakeStt(), FakeTts(), FakeDetector)
    app = create_app(
        settings,
        ScriptedLlm([_say("Hola")]),
        FakeCore(),
        ConversationStore(),
        voice_engines=engines,
    )
    return TestClient(app, base_url="http://127.0.0.1:8001")


ORIGIN = {"origin": "http://127.0.0.1:8001", "host": "127.0.0.1:8001"}
START = json.dumps({"phone": "+34600000001"})


def test_without_voice_turned_on_neither_the_page_nor_the_socket_exist() -> None:
    """Most installations have no engines, or any need for them."""
    client = _app(voice=False)

    assert client.get("/voice").status_code == 404
    with pytest.raises(WebSocketDisconnect), client.websocket_connect("/ws/voice", headers=ORIGIN):
        pass


def test_the_page_is_served_under_the_policy_of_the_service() -> None:
    """With its own files only."""
    response = _app().get("/voice")

    assert response.status_code == 200
    assert "default-src 'self'" in response.headers["Content-Security-Policy"]
    assert "<script src" in response.text


def test_a_call_from_another_page_is_refused_before_anything_else() -> None:
    """Which would otherwise bind a call to whatever phone that page chose."""
    client = _app()

    with (
        pytest.raises(WebSocketDisconnect) as closed,
        client.websocket_connect("/ws/voice", headers={**ORIGIN, "origin": "http://evil.example"}),
    ):
        pass

    assert closed.value.code == 4403


def test_a_call_starts_and_says_the_rate_of_its_audio() -> None:
    """The page needs it to play what it receives."""
    with _app().websocket_connect("/ws/voice", headers=ORIGIN) as socket:
        socket.send_text(START)

        assert socket.receive_json() == {"type": "ready", "output_sample_rate": TTS_RATE}


@pytest.mark.parametrize(
    "start",
    ["not json", "{}", '{"phone": "600"}', '{"phone": "+34600000001", "patient_id": "x"}'],
)
def test_a_call_that_does_not_say_who_it_is_ends(start: str) -> None:
    """Nothing about it is believed, including extra fields."""
    with _app().websocket_connect("/ws/voice", headers=ORIGIN) as socket:
        socket.send_text(start)

        with pytest.raises(WebSocketDisconnect) as closed:
            socket.receive_json()

    assert closed.value.code == 4400


def test_a_piece_of_audio_too_large_ends_the_call() -> None:
    """The page never sends that much at once."""
    with _app().websocket_connect("/ws/voice", headers=ORIGIN) as socket:
        socket.send_text(START)
        socket.receive_json()
        socket.send_bytes(b"\x00" * (MAX_FRAME_BYTES + 1))

        with pytest.raises(WebSocketDisconnect) as closed:
            socket.receive_json()

    assert closed.value.code == 1009


def test_too_many_calls_at_once_are_refused() -> None:
    """Each one keeps the CPU busy, and the rest would suffer."""
    client = _app()
    open_calls = []
    try:
        for _ in range(MAX_CALLS):
            socket = client.websocket_connect("/ws/voice", headers=ORIGIN)
            socket.__enter__()
            socket.send_text(START)
            socket.receive_json()
            open_calls.append(socket)

        with (
            pytest.raises(WebSocketDisconnect) as closed,
            client.websocket_connect("/ws/voice", headers=ORIGIN),
        ):
            pass

        assert closed.value.code == 4429
    finally:
        for socket in open_calls:
            socket.__exit__(None, None, None)


def test_a_call_that_ended_frees_its_place() -> None:
    """Otherwise the service would run out of them."""
    client = _app()

    for _ in range(MAX_CALLS + 2):
        with client.websocket_connect("/ws/voice", headers=ORIGIN) as socket:
            socket.send_text(START)
            socket.receive_json()


def test_calls_for_one_phone_are_limited() -> None:
    """As with the other channels."""
    client = _app(max_conversations_per_phone_per_hour=1)
    with client.websocket_connect("/ws/voice", headers=ORIGIN) as socket:
        socket.send_text(START)
        socket.receive_json()

    with client.websocket_connect("/ws/voice", headers=ORIGIN) as socket:
        socket.send_text(START)
        with pytest.raises(WebSocketDisconnect) as closed:
            socket.receive_json()

    assert closed.value.code == 4429


def test_a_call_through_the_socket_gets_an_answer() -> None:
    """The whole way, with scripted engines."""
    stt = FakeStt()
    stt.queue.append(Transcript("hola", SURE))
    settings = Settings(
        _env_file=None,  # type: ignore[call-arg]
        allowed_hosts=["127.0.0.1", "testserver"],
        voice_enabled=True,
    )
    app = create_app(
        settings,
        ScriptedLlm([_say("Buenas")]),
        FakeCore(),
        ConversationStore(),
        voice_engines=VoiceEngines(stt, FakeTts(), FakeDetector),
    )
    client = TestClient(app, base_url="http://127.0.0.1:8001")

    with client.websocket_connect("/ws/voice", headers=ORIGIN) as socket:
        socket.send_text(START)
        socket.receive_json()
        socket.send_bytes(SPEECH * 7)
        socket.send_bytes(SPEECH * 3 + SILENCE)
        socket.send_bytes(SILENCE * 7)
        socket.send_bytes(SILENCE * 7)
        socket.send_bytes(SILENCE * 7)
        replies: list[str] = []
        audio = 0
        while len(replies) < 2 or audio < 2:
            message = socket.receive()
            if message.get("text"):
                event = json.loads(message["text"])
                if event["type"] == "reply":
                    replies.append(event["text"])
            elif message.get("bytes"):
                audio += 1

    assert replies == ["Un momento.", "Buenas"]
    assert CLINIC["emergencyNumber"] == "112"
