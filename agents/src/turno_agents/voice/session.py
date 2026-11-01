"""One voice call: when someone starts and stops talking, and what is said back.

Voice adds what no text channel has. A transcription can be wrong, so a "yes" only counts if the
engine is sure of it. And someone can talk over a question, so a "yes" only counts if the question
was heard to the end. The agent, its guards and its rules are the same as everywhere else.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import logging
import re
import threading
import time
from collections import deque
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from typing import Protocol

from turno_agents.agent import AgentService
from turno_agents.conversation import Conversation
from turno_agents.guards import Triage, is_affirmative, triage
from turno_agents.voice.engines import FRAME_BYTES, INPUT_SAMPLE_RATE, VoiceEngines

LOG = logging.getLogger(__name__)

_FRAME_SECONDS = FRAME_BYTES / 2 / INPUT_SAMPLE_RATE
_SPEECH_PROBABILITY = 0.5
# Enough to ignore a cough, and little enough not to clip the start of a word.
_FRAMES_TO_START = 3
_SECONDS_OF_SILENCE_TO_END = 0.7
_PRE_ROLL_SECONDS = 0.3
_MAX_UTTERANCE_SECONDS = 20.0
# Below this, what was heard is not worth acting on, and a person is asked to say it again.
MIN_CONFIDENCE = 0.45
# Agreeing changes an appointment, so it takes more certainty than anything else.
MIN_CONFIDENCE_TO_AGREE = 0.6

_TEXTS = {
    "es": {
        "moment": "Un momento.",
        "not_understood": "Perdona, no te he entendido bien. ¿Puedes repetirlo?",
        "repeat_yes": "Perdona, quiero estar seguro de haberte entendido.",
    },
    "en": {
        "moment": "One moment.",
        "not_understood": "Sorry, I did not catch that. Could you say it again?",
        "repeat_yes": "Sorry, I want to be sure I understood you.",
    },
}
_LIST_MARK = re.compile(r"^\s*[-*•]\s+", re.MULTILINE)
_EMPHASIS = re.compile(r"[*_`#]+")


def speakable(text: str) -> str:
    """Prepare a reply to be read aloud, since marks and symbols make no sense to a listener.

    Args:
        text: The reply.

    Returns:
        The text to say.
    """
    plain = _EMPHASIS.sub("", _LIST_MARK.sub("", text))
    plain = plain.replace("€", " euros")
    return " ".join(plain.split())


class VoiceSink(Protocol):
    """Where a session sends what it has to say."""

    def event(self, payload: dict[str, object]) -> None:
        """Send something to show.

        Args:
            payload: The message.
        """
        ...

    def audio(self, samples: bytes) -> None:
        """Send something to play.

        Args:
            samples: 16-bit mono audio at the rate of the text-to-speech engine.
        """
        ...


class VoiceSession:
    """Listens to one person, and answers through the agent."""

    def __init__(
        self,
        agent: AgentService,
        conversation: Conversation,
        engines: VoiceEngines,
        sink: VoiceSink,
        allow_message: Callable[[], bool] = lambda: True,
        now: Callable[[], float] = time.monotonic,
    ) -> None:
        """Start the session.

        Args:
            agent: The agent.
            conversation: The conversation, already bound to its patient.
            engines: What listens and speaks.
            sink: Where things are sent.
            allow_message: Whether the limit of messages allows another one.
            now: Provides the current time, in seconds.
        """
        self._agent = agent
        self._conversation = conversation
        self._engines = engines
        self._sink = sink
        self._allow_message = allow_message
        self._now = now
        self._detector = engines.new_detector()
        self._lock = threading.Lock()
        self._buffer = bytearray()
        self._pre_roll: deque[bytes] = deque(maxlen=int(_PRE_ROLL_SECONDS / _FRAME_SECONDS))
        self._utterance = bytearray()
        self._speaking = False
        self._voiced_frames = 0
        self._silent_frames = 0
        self._playing_until = 0.0
        # What was last said must be said to the end for a yes to it to count.
        self._question_heard = True
        # Grows at each interruption, so that an answer to something older is never played.
        self._generation = 0
        # Hearing and answering apart, so that an emergency need not wait for the model.
        self._hearing = ThreadPoolExecutor(max_workers=1, thread_name_prefix="voice-hear")
        self._turns = ThreadPoolExecutor(max_workers=1, thread_name_prefix="voice-turn")

    def feed(self, data: bytes) -> None:
        """Take audio from the patient.

        Args:
            data: 16-bit mono audio at the input rate, of any length.
        """
        self._buffer.extend(data)
        while len(self._buffer) >= FRAME_BYTES:
            frame = bytes(self._buffer[:FRAME_BYTES])
            del self._buffer[:FRAME_BYTES]
            self._take_frame(frame)

    def _take_frame(self, frame: bytes) -> None:
        voiced = self._detector.probability(frame) >= _SPEECH_PROBABILITY
        if not self._speaking:
            self._voiced_frames = self._voiced_frames + 1 if voiced else 0
            self._pre_roll.append(frame)
            if self._voiced_frames >= _FRAMES_TO_START:
                self._start_of_speech()
            return
        self._utterance.extend(frame)
        self._silent_frames = 0 if voiced else self._silent_frames + 1
        too_quiet = self._silent_frames * _FRAME_SECONDS >= _SECONDS_OF_SILENCE_TO_END
        too_long = len(self._utterance) / 2 / INPUT_SAMPLE_RATE >= _MAX_UTTERANCE_SECONDS
        if too_quiet or too_long:
            self._end_of_speech()

    def _start_of_speech(self) -> None:
        self._speaking = True
        self._silent_frames = 0
        self._utterance = bytearray(b"".join(self._pre_roll))
        self._pre_roll.clear()
        with self._lock:
            interrupted = self._now() < self._playing_until
            if interrupted:
                self._question_heard = False
                self._playing_until = 0.0
                self._generation += 1
        if interrupted:
            self._sink.event({"type": "stop"})

    def _end_of_speech(self) -> None:
        samples = bytes(self._utterance)
        self._utterance = bytearray()
        self._speaking = False
        self._voiced_frames = 0
        self._hearing.submit(self._guarded, self._hear, samples, self._now())

    def _guarded(self, task: Callable[..., None], *args: object) -> None:
        try:
            task(*args)
        except Exception:
            # Nobody is waiting for this thread, so a failure would otherwise be silence.
            LOG.exception("A voice turn failed")
            self._say(self._text("not_understood"), self._current_generation())

    def _current_generation(self) -> int:
        with self._lock:
            return self._generation

    def _text(self, key: str) -> str:
        return _TEXTS.get(self._conversation.language, _TEXTS["en"])[key]

    def _hear(self, samples: bytes, ended_at: float) -> None:
        heard = self._engines.speech_to_text.transcribe(samples, self._conversation.language)
        hear_seconds = self._now() - ended_at
        text = heard.text.strip()
        if not text:
            return
        self._sink.event({"type": "heard", "text": text})
        # First of all, and however unsure: mistaking something for an emergency costs little.
        if triage(text) is Triage.EMERGENCY:
            reply = self._agent.answer_emergency(self._conversation)
            self._interrupt_what_is_playing()
            self._say(reply, self._current_generation(), question=False)
            self._timing(hear_seconds, ended_at)
            return
        self._turns.submit(
            self._guarded, self._turn, text, heard.confidence, hear_seconds, ended_at
        )

    def _interrupt_what_is_playing(self) -> None:
        with self._lock:
            self._generation += 1
            self._playing_until = 0.0
        self._sink.event({"type": "stop"})

    def _turn(self, text: str, confidence: float, hear_seconds: float, ended_at: float) -> None:
        generation = self._current_generation()
        if confidence < MIN_CONFIDENCE:
            self._say(self._text("not_understood"), generation, question=False)
            self._timing(hear_seconds, ended_at)
            return
        if not self._allow_message():
            LOG.warning("A caller went over the limit of messages, and is not being answered")
            return
        pending = self._conversation.pending
        if pending is not None and is_affirmative(text, pending.action.type):
            with self._lock:
                heard_to_the_end = self._question_heard and self._now() >= self._playing_until
            if confidence < MIN_CONFIDENCE_TO_AGREE or not heard_to_the_end:
                # Nothing runs: they get the question again, in the code's own words.
                self._say(f"{self._text('repeat_yes')} {pending.description}", generation)
                self._timing(hear_seconds, ended_at)
                return
        self._say(self._text("moment"), generation, question=False)
        first_audio = self._now() - ended_at
        with self._conversation.lock:
            reply = self._agent.handle(self._conversation, text)
        if generation == self._current_generation():
            self._say(reply, generation)
        else:
            # It will never be heard, so nothing in it can be agreed to.
            self._question_heard = False
        self._timing(hear_seconds, ended_at, first_audio)

    def _say(self, text: str, generation: int, question: bool = True) -> None:
        samples = self._engines.text_to_speech.synthesize(
            speakable(text), self._conversation.language
        )
        seconds = len(samples) / 2 / self._engines.text_to_speech.sample_rate
        with self._lock:
            if generation != self._generation:
                return
            self._playing_until = max(self._playing_until, self._now()) + seconds
            if question:
                self._question_heard = True
        self._sink.event({"type": "reply", "text": text})
        self._sink.audio(samples)

    def _timing(
        self, hear_seconds: float, ended_at: float, first_audio: float | None = None
    ) -> None:
        self._sink.event(
            {
                "type": "timing",
                "hear_seconds": round(hear_seconds, 3),
                "first_audio_seconds": round(first_audio, 3) if first_audio is not None else None,
                "reply_seconds": round(self._now() - ended_at, 3),
            }
        )

    def drain(self) -> None:
        """Wait for what is in progress, for whoever needs to know it has finished."""
        self._hearing.submit(lambda: None).result()
        self._turns.submit(lambda: None).result()

    def close(self) -> None:
        """End the session. An answer still on its way is never played."""
        with self._lock:
            self._generation += 1
        self._hearing.shutdown(wait=False, cancel_futures=True)
        self._turns.shutdown(wait=False, cancel_futures=True)
