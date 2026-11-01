"""Scores what the voice channel heard, and writes the report. It needs no engine, so CI can run it.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import json
from pathlib import Path
from typing import Literal

import yaml
from pydantic import BaseModel, ConfigDict

from turno_agents.guards import Triage, is_affirmative, normalize, triage
from turno_agents.voice.session import MIN_CONFIDENCE, MIN_CONFIDENCE_TO_AGREE

PHRASES = Path(__file__).parent / "phrases.yaml"
RESULTS = Path(__file__).parent / "results.json"
REPORT = Path(__file__).parent / "REPORT.md"

Kind = Literal["emergency", "agree", "not_agree", "words", "quiet"]
Condition = Literal["clean", "noisy"]


class Phrase(BaseModel):
    """Something to say, and what must survive the transcription."""

    model_config = ConfigDict(extra="forbid")

    id: str
    language: Literal["es", "en"]
    kind: Kind
    text: str
    words: list[str] = []


class Heard(BaseModel):
    """What the engine made of a phrase."""

    model_config = ConfigDict(extra="forbid")

    phrase_id: str
    condition: Condition
    text: str
    confidence: float
    seconds: float
    audio_seconds: float


class CallTiming(BaseModel):
    """How long one turn of a real call took, with the real model."""

    model_config = ConfigDict(extra="forbid")

    scenario: str
    hear_seconds: float
    first_audio_seconds: float | None
    reply_seconds: float


class CallOutcome(BaseModel):
    """How a real call ended."""

    model_config = ConfigDict(extra="forbid")

    scenario: str
    passed: bool
    detail: str


class Results(BaseModel):
    """Everything measured, which is what the report is written from."""

    model_config = ConfigDict(extra="forbid")

    whisper_model: str
    agent_model: str | None = None
    heard: list[Heard]
    synthesis_seconds_per_audio_second: float
    timings: list[CallTiming] = []
    outcomes: list[CallOutcome] = []


def load_phrases(path: Path = PHRASES) -> list[Phrase]:
    """Read the phrases.

    Args:
        path: The file.

    Returns:
        The phrases.
    """
    return [
        Phrase.model_validate(entry) for entry in yaml.safe_load(path.read_text(encoding="utf-8"))
    ]


def survived(phrase: Phrase, heard: Heard) -> bool:
    """Tell whether what mattered in a phrase was heard, by the rules the channel really applies.

    Args:
        phrase: The phrase.
        heard: What the engine made of it.

    Returns:
        Whether the channel would have done the right thing.
    """
    if phrase.kind == "emergency":
        return triage(heard.text) is Triage.EMERGENCY
    if phrase.kind == "agree":
        return is_affirmative(heard.text) and heard.confidence >= MIN_CONFIDENCE_TO_AGREE
    if phrase.kind == "quiet":
        return triage(heard.text) is not Triage.EMERGENCY and not is_affirmative(heard.text)
    if phrase.kind == "not_agree":
        return not is_affirmative(heard.text)
    text = normalize(heard.text)
    return all(word in text for word in phrase.words) and heard.confidence >= MIN_CONFIDENCE


def percentile(values: list[float], share: float) -> float:
    """Find a value by its place in the sorted list.

    Args:
        values: The values.
        share: Between zero and one.

    Returns:
        The value, or zero if there are none.
    """
    if not values:
        return 0.0
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(share * len(ordered)))]


_KIND_NAMES = {
    "emergency": "Emergencies reach triage",
    "agree": "A yes is taken as a yes",
    "not_agree": "Anything else is not taken as a yes",
    "words": "Days and treatments are heard",
    "quiet": "Noise and unrelated speech become neither a yes nor an emergency",
}


def render_report(results: Results | None, phrases: list[Phrase]) -> str:
    """Write the report.

    Args:
        results: What was measured, if anything.
        phrases: The phrases.

    Returns:
        The report, in Markdown.
    """
    lines = [
        "# How well the voice channel hears, and how long it takes",
        "",
        "Measured on this project's own machine, on the CPU. The audio comes from Piper, the same "
        "engine that speaks, with and without added noise. That is much cleaner than a real person "
        "on a real phone, **so these figures are optimistic**: they find what breaks, and do not "
        "predict how it would go with real callers.",
        "",
    ]
    if results is None:
        lines.append("**Nothing measured yet.**")
        return "\n".join(lines) + "\n"
    by_id = {phrase.id: phrase for phrase in phrases}
    current = [item for item in results.heard if item.phrase_id in by_id]
    lines.extend(
        [
            f"Whisper model: `{results.whisper_model}`.",
            "",
            "| What must hold | Clean audio | With noise |",
            "| --- | ---: | ---: |",
        ]
    )
    for kind, name in _KIND_NAMES.items():
        cells = []
        for condition in ("clean", "noisy"):
            group = [
                item
                for item in current
                if item.condition == condition and by_id[item.phrase_id].kind == kind
            ]
            good = sum(survived(by_id[item.phrase_id], item) for item in group)
            cells.append(f"{good} of {len(group)}")
        lines.append(f"| {name} | {cells[0]} | {cells[1]} |")
    failures = [item for item in current if not survived(by_id[item.phrase_id], item)]
    lines.extend(["", "## What was not heard right", ""])
    if failures:
        lines.extend(
            ["| Phrase | Audio | Said | Heard | Confidence |", "| --- | --- | --- | --- | ---: |"]
        )
        for item in failures:
            said = by_id[item.phrase_id].text
            lines.append(
                f"| {item.phrase_id} | {item.condition} | {said} | {json.dumps(item.text, ensure_ascii=False)} | {item.confidence:.2f} |"
            )
    else:
        lines.append("Nothing.")
    stt = [item.seconds for item in current]
    lines.extend(
        [
            "",
            "## How long it takes",
            "",
            f"- Transcribing a phrase: median {percentile(stt, 0.5):.2f} s, p95 {percentile(stt, 0.95):.2f} s.",
            f"- Speaking: {results.synthesis_seconds_per_audio_second:.3f} s of work per second of audio.",
        ]
    )
    if results.timings:
        first = [
            t.first_audio_seconds for t in results.timings if t.first_audio_seconds is not None
        ]
        replies = [t.reply_seconds for t in results.timings if t.first_audio_seconds is not None]
        hear = [t.hear_seconds for t in results.timings]
        lines.extend(
            [
                "",
                f"From the end of speech, on {len(results.timings)} turns of calls with `{results.agent_model}`, "
                "through the Claude CLI. A turn with no model, such as an emergency, only takes the transcription.",
                "",
                "| Until | Median | p95 |",
                "| --- | ---: | ---: |",
                f"| The transcript | {percentile(hear, 0.5):.2f} s | {percentile(hear, 0.95):.2f} s |",
                f"| The first audio, a fixed holding phrase | {percentile(first, 0.5):.2f} s | {percentile(first, 0.95):.2f} s |",
                f"| The real answer | {percentile(replies, 0.5):.2f} s | {percentile(replies, 0.95):.2f} s |",
                "",
                "The wait for the real answer is mostly the model through the CLI. It is too long for a "
                "natural conversation, and nothing here hides that.",
            ]
        )
    if results.outcomes:
        good = sum(outcome.passed for outcome in results.outcomes)
        lines.extend(
            [
                "",
                "## Whole calls with the real model",
                "",
                f"**{good} of {len(results.outcomes)} went as they should.**",
                "",
            ]
        )
        lines.extend(["| Call | Result | |", "| --- | --- | --- |"])
        for outcome in results.outcomes:
            lines.append(
                f"| {outcome.scenario} | {'pass' if outcome.passed else '**fail**'} | {outcome.detail} |"
            )
    return "\n".join(lines) + "\n"


def load_results(path: Path = RESULTS) -> Results | None:
    """Read what was measured.

    Args:
        path: The file.

    Returns:
        The results, if any.
    """
    return Results.model_validate_json(path.read_text(encoding="utf-8")) if path.exists() else None
