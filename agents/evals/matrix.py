"""The same scenarios with several models, each in a folder of its own.

The patient simulator stays the same for every model, or the comparison would be measuring two things
at once. Recordings of the default model and the gate that depends on them are not touched.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

from dataclasses import dataclass
from pathlib import Path

from evals.gate import Graded, grade_bank
from evals.scenarios import Scenario

MATRIX = Path(__file__).parent / "matrix"
REPORT = Path(__file__).parent / "MATRIX.md"


def folder_of(model: str) -> Path:
    """Find where a model's recordings go.

    Args:
        model: The model.

    Returns:
        Its folder.

    Raises:
        ValueError: If the name could point outside the matrix.
    """
    if not model.replace("-", "").replace(".", "").isalnum():
        raise ValueError("A model name is letters, digits, dots and hyphens")
    return MATRIX / model


def percentile(values: list[float], share: float) -> float:
    """Find a value by its place in the sorted list, without interpolating.

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


@dataclass(frozen=True)
class ModelResult:
    """What a model did with the bank.

    Attributes:
        model: The model.
        recorded: Scenarios with a current recording.
        passed: Of those, how many passed every check.
        stale: Scenarios whose recording no longer applies, which do not count.
        calls: Calls to the agent's model.
        input_tokens: Tokens read, the simulator's included.
        output_tokens: Tokens written, the simulator's included.
        estimated_cost_usd: What it would cost on the API, the simulator's included.
        p50_seconds: Median time of an agent's call.
        p95_seconds: Slow time of an agent's call.
    """

    model: str
    recorded: int
    passed: int
    stale: int
    calls: int
    input_tokens: int
    output_tokens: int
    estimated_cost_usd: float
    p50_seconds: float
    p95_seconds: float


def summarize(model: str, graded: list[Graded]) -> ModelResult:
    """Add up a model's recordings.

    Args:
        model: The model.
        graded: Its scenarios, graded.

    Returns:
        The summary.
    """
    current = [item for item in graded if item.recording is not None and not item.stale]
    usages = [item.recording.usage for item in current if item.recording is not None]
    seconds = [value for usage in usages for value in usage.agent_call_seconds]
    return ModelResult(
        model=model,
        recorded=len(current),
        passed=sum(item.ok for item in current),
        stale=sum(item.stale for item in graded),
        calls=sum(usage.agent_calls for usage in usages),
        input_tokens=sum(usage.input_tokens for usage in usages),
        output_tokens=sum(usage.output_tokens for usage in usages),
        estimated_cost_usd=sum(usage.estimated_cost_usd for usage in usages),
        p50_seconds=percentile(seconds, 0.5),
        p95_seconds=percentile(seconds, 0.95),
    )


def recorded_models(root: Path = MATRIX) -> list[str]:
    """List the models that have a folder.

    Args:
        root: The folder of the matrix.

    Returns:
        Their names, sorted.
    """
    return sorted(path.name for path in root.iterdir() if path.is_dir()) if root.exists() else []


def render_report(
    bank: list[Scenario], root: Path = MATRIX, baseline: tuple[str, Path] | None = None
) -> str:
    """Write the comparison.

    Args:
        bank: The scenarios.
        root: The folder of the matrix.
        baseline: A model recorded elsewhere, with its folder: the one the gate depends on.

    Returns:
        The report, in Markdown.
    """
    lines = [
        "# The same bank with several models",
        "",
        "Each scenario ran once per model, so a difference of a scenario or two may be chance. The "
        "patient simulator is the same in every column. Cost is what the API would charge, simulator "
        "included: the plan in use has no cost per call.",
        "",
    ]
    folders = {model: root / model for model in recorded_models(root)}
    if baseline is not None and folders:
        folders[baseline[0]] = baseline[1]
    models = sorted(folders)
    if not models:
        lines.append(f"**Nothing recorded yet**, out of {len(bank)} scenarios in the bank.")
        return "\n".join(lines) + "\n"
    results = [summarize(model, grade_bank(bank, folders[model], model)) for model in models]
    lines.extend(
        [
            "| Model | Recorded | Passed | Agent calls | Tokens read | Tokens written | Est. cost | Cost per scenario | Call p50 | Call p95 |",
            "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        ]
    )
    for result in results:
        per_scenario = result.estimated_cost_usd / result.recorded if result.recorded else 0.0
        share = f"{result.passed * 100 / result.recorded:.0f}%" if result.recorded else "-"
        lines.append(
            f"| `{result.model}` | {result.recorded}/{len(bank)} | {result.passed} ({share}) | {result.calls} | "
            f"{result.input_tokens} | {result.output_tokens} | ${result.estimated_cost_usd:.4f} | "
            f"${per_scenario:.4f} | {result.p50_seconds:.1f} s | {result.p95_seconds:.1f} s |"
        )
    lines.extend(["", "## Scenarios on which the models differ", ""])
    by_model = {model: grade_bank(bank, folders[model], model) for model in models}
    differing = [
        index
        for index in range(len(bank))
        if len(
            {
                item[index].ok
                for item in by_model.values()
                if item[index].recorded and not item[index].stale
            }
        )
        > 1
    ]
    if not differing:
        lines.append("None, among the scenarios recorded.")
    else:
        lines.extend(
            ["| Scenario | " + " | ".join(models) + " |", "| --- |" + " --- |" * len(models)]
        )
        for index in differing:
            cells = []
            for model in models:
                item = by_model[model][index]
                cells.append(
                    "-" if not item.recorded or item.stale else ("pass" if item.ok else "**fail**")
                )
            lines.append(f"| {bank[index].id} | " + " | ".join(cells) + " |")
    return "\n".join(lines) + "\n"
