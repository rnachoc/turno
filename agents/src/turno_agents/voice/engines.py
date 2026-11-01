"""The engines that listen and speak. All of them run on this machine, with no account and no cost.

They are an optional install, so everything that imports them does it when building one. Nothing
else in the service, and nothing in CI, needs them.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import math
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

# What the browser sends: 16-bit mono at this rate, in frames of this many samples.
INPUT_SAMPLE_RATE = 16_000
FRAME_SAMPLES = 512
FRAME_BYTES = FRAME_SAMPLES * 2
# How many frames the speech detector reads each time. It keeps no state between calls.
_DETECTOR_WINDOW_FRAMES = 16
# Above this, Whisper itself believes there was nobody speaking.
_MAX_NO_SPEECH = 0.6
_VOICES = {"es": "es_ES-davefx-medium", "en": "en_US-lessac-medium"}
# Tells Whisper what kind of words to expect. These are the ones that hurt most to get wrong.
_VOCABULARY = {
    "es": (
        "Clínica dental. Sí, confirmo. Vale. De acuerdo. No, gracias. Cita, revisión, empaste, "
        "limpieza, blanqueamiento, anular, cancelar. Lunes, martes, miércoles, jueves, viernes. "
        "No puedo respirar. Me ahogo. Me duele el pecho. Cara hinchada. No para de sangrar. "
        "Me he desmayado."
    ),
    "en": (
        "Dental clinic. Yes, please. No, thanks. Appointment, check-up, filling, cancel. Monday, "
        "Tuesday, Wednesday, Thursday, Friday. I can't breathe. Chest pain. Swollen face. The "
        "bleeding won't stop. I fainted."
    ),
}


@dataclass(frozen=True)
class Transcript:
    """What was heard.

    Attributes:
        text: The words.
        confidence: How sure the engine is, from nothing to one.
    """

    text: str
    confidence: float


class SpeechToText(Protocol):
    """Turns speech into text."""

    def transcribe(self, samples: bytes, language: str) -> Transcript:
        """Transcribe an utterance.

        Args:
            samples: 16-bit mono audio at the input rate.
            language: The patient's language.

        Returns:
            What was heard.
        """
        ...


class TextToSpeech(Protocol):
    """Turns text into speech."""

    sample_rate: int

    def synthesize(self, text: str, language: str) -> bytes:
        """Say something.

        Args:
            text: The words.
            language: The language.

        Returns:
            16-bit mono audio at this engine's rate.
        """
        ...


class SpeechDetector(Protocol):
    """Tells speech from everything else."""

    def probability(self, frame: bytes) -> float:
        """Say how likely it is that the latest frame holds speech.

        Args:
            frame: One frame of audio.

        Returns:
            A probability.
        """
        ...


@dataclass(frozen=True)
class VoiceEngines:
    """Everything a voice session needs.

    Attributes:
        speech_to_text: Listens.
        text_to_speech: Speaks.
        new_detector: Builds a detector for one session, since each one keeps its recent audio.
    """

    speech_to_text: SpeechToText
    text_to_speech: TextToSpeech
    new_detector: Any


class WhisperSpeechToText:
    """Transcribes with faster-whisper, on the CPU."""

    def __init__(self, model: str, download_root: Path) -> None:
        """Load the model.

        Args:
            model: Its size, such as ``base``.
            download_root: Where the models are kept.
        """
        from faster_whisper import WhisperModel

        self._model = WhisperModel(
            model, device="cpu", compute_type="int8", download_root=str(download_root)
        )

    def transcribe(self, samples: bytes, language: str) -> Transcript:
        """Transcribe an utterance.

        Args:
            samples: 16-bit mono audio at the input rate.
            language: The patient's language.

        Returns:
            What was heard, or nothing if Whisper believes nobody spoke.
        """
        import numpy as np

        audio = np.frombuffer(samples, dtype=np.int16).astype(np.float32) / 32768.0
        segments, _ = self._model.transcribe(
            audio,
            language=language,
            beam_size=1,
            vad_filter=False,
            initial_prompt=_VOCABULARY.get(language),
        )
        heard = [segment for segment in segments if segment.no_speech_prob < _MAX_NO_SPEECH]
        if not heard:
            return Transcript("", 0.0)
        confidence = math.exp(sum(segment.avg_logprob for segment in heard) / len(heard))
        return Transcript(" ".join(segment.text.strip() for segment in heard), confidence)


class PiperTextToSpeech:
    """Speaks with Piper, with one voice per language."""

    def __init__(self, models: Path) -> None:
        """Load the voices.

        Args:
            models: Where the voices are kept.
        """
        from piper import PiperVoice

        self._voices = {
            language: PiperVoice.load(models / f"{name}.onnx") for language, name in _VOICES.items()
        }
        self.sample_rate: int = self._voices["es"].config.sample_rate

    def synthesize(self, text: str, language: str) -> bytes:
        """Say something.

        Args:
            text: The words.
            language: The language, in English if there is no voice for it.

        Returns:
            16-bit mono audio at this engine's rate.
        """
        voice = self._voices.get(language, self._voices["en"])
        return b"".join(chunk.audio_int16_bytes for chunk in voice.synthesize(text))


class SileroSpeechDetector:
    """Detects speech with the Silero model that faster-whisper ships."""

    def __init__(self) -> None:
        """Load the model and start with silence."""
        from faster_whisper.vad import get_vad_model

        self._model = get_vad_model()
        self._window = bytes(FRAME_BYTES * _DETECTOR_WINDOW_FRAMES)

    def probability(self, frame: bytes) -> float:
        """Say how likely it is that the latest frame holds speech.

        Args:
            frame: One frame of audio.

        Returns:
            A probability.
        """
        import numpy as np

        self._window = self._window[len(frame) :] + frame
        audio = np.frombuffer(self._window, dtype=np.int16).astype(np.float32) / 32768.0
        return float(self._model(audio).flatten()[-1])


def load_engines(models: Path, whisper_model: str) -> VoiceEngines:
    """Load the real engines, which takes some seconds.

    Args:
        models: Where the models are kept.
        whisper_model: The size of the Whisper model.

    Returns:
        The engines.
    """
    return VoiceEngines(
        WhisperSpeechToText(whisper_model, models / "whisper"),
        PiperTextToSpeech(models),
        SileroSpeechDetector,
    )
