"""Tests of how the voice channel is scored, with no engine.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import pytest

from voice_eval.measure import (
    REPORT,
    Heard,
    Phrase,
    Results,
    load_phrases,
    load_results,
    percentile,
    render_report,
    survived,
)


def _heard(text: str, confidence: float = 0.9) -> Heard:
    return Heard(
        phrase_id="x",
        condition="clean",
        text=text,
        confidence=confidence,
        seconds=1.0,
        audio_seconds=1.0,
    )


def _phrase(kind: str, words: list[str] | None = None) -> Phrase:
    return Phrase(id="x", language="es", kind=kind, text="x", words=words or [])  # type: ignore[arg-type]


def test_the_report_is_up_to_date() -> None:
    """A stale report would publish figures that are no longer true."""
    assert REPORT.read_text(encoding="utf-8") == render_report(load_results(), load_phrases())


def test_the_bank_holds_what_matters_most() -> None:
    """Emergencies, consent, and what must not become either."""
    kinds = [phrase.kind for phrase in load_phrases()]

    for kind in ("emergency", "agree", "not_agree", "words", "quiet"):
        assert kinds.count(kind) >= 5, kind


@pytest.mark.parametrize(
    ("kind", "text", "confidence", "expected"),
    [
        ("emergency", "no puedo respirar", 0.2, True),
        ("emergency", "hola", 0.9, False),
        ("agree", "sí", 0.9, True),
        ("agree", "sí", 0.3, False),
        ("agree", "tiene", 0.9, False),
        ("not_agree", "no gracias", 0.9, True),
        ("not_agree", "sí", 0.9, False),
        ("quiet", "hace buen tiempo", 0.9, True),
        ("quiet", "sí", 0.9, False),
        ("quiet", "no puedo respirar", 0.9, False),
        ("quiet", "", 0.0, True),
    ],
)
def test_a_phrase_survives_by_the_rules_the_channel_applies(
    kind: str, text: str, confidence: float, expected: bool
) -> None:
    """Emergencies count however unsure the engine is, and a yes does not."""
    assert survived(_phrase(kind), _heard(text, confidence)) is expected


def test_words_must_all_be_heard_with_enough_confidence() -> None:
    """Accents do not matter, and something unclear is asked again anyway."""
    phrase = _phrase("words", ["revision", "martes"])

    assert survived(phrase, _heard("una Revisión el martes"))
    assert not survived(phrase, _heard("una revisión"))
    assert not survived(phrase, _heard("una revisión el martes", 0.2))


def test_percentiles_come_from_real_values() -> None:
    """Without interpolating, and with nothing to go on."""
    assert percentile([], 0.5) == 0.0
    assert percentile([3.0, 1.0, 2.0], 0.5) == 2.0


def test_with_nothing_measured_it_says_so() -> None:
    """An empty table would look like a result."""
    assert "Nothing measured yet" in render_report(None, load_phrases())


def test_the_report_warns_that_the_audio_is_synthetic() -> None:
    """The most important thing to know about these figures."""
    results = Results(whisper_model="small", heard=[], synthesis_seconds_per_audio_second=0.1)

    assert "these figures are optimistic" in render_report(results, load_phrases())
