"""The regression gate and the report. Both work on recordings alone, with no model and no core.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import json
import statistics
from dataclasses import dataclass, field
from pathlib import Path

from evals.graders import Check, grade, passed
from evals.recording import RECORDINGS, Recording, load_recording
from evals.runner import scenario_fingerprint
from evals.scenarios import Scenario

BASELINE = Path(__file__).parent / "baseline.json"
REPORT = Path(__file__).parent / "REPORT.md"


@dataclass
class Graded:
    """A scenario with its recording and its checks.

    Attributes:
        scenario: The scenario.
        recording: Its recording, if it has one.
        checks: The outcome of each check.
        stale: Whether the prompts, the schema, the model or the scenario changed since recording.
    """

    scenario: Scenario
    recording: Recording | None = None
    checks: list[Check] = field(default_factory=list)
    stale: bool = False

    @property
    def recorded(self) -> bool:
        """Tell whether the scenario has a recording."""
        return self.recording is not None

    @property
    def ok(self) -> bool:
        """Tell whether the scenario was recorded and passed."""
        return self.recorded and passed(self.checks)


def grade_bank(
    scenarios: list[Scenario], folder: Path = RECORDINGS, model: str | None = None
) -> list[Graded]:
    """Grade every scenario from its recording.

    Args:
        scenarios: The bank.
        folder: The folder with the recordings.
        model: The model expected in the fingerprint; by default, the one each recording names.

    Returns:
        One entry per scenario.
    """
    graded = []
    for scenario in scenarios:
        recording = load_recording(scenario.id, folder)
        if recording is None:
            graded.append(Graded(scenario))
            continue
        expected = scenario_fingerprint(scenario, model or recording.model)
        graded.append(
            Graded(
                scenario,
                recording,
                grade(scenario, recording),
                stale=recording.fingerprint != expected,
            )
        )
    return graded


def baseline_of(graded: list[Graded]) -> dict[str, bool]:
    """Describe the current results, to accept them as what must not get worse.

    Args:
        graded: The graded bank.

    Returns:
        Whether each recorded scenario passes.
    """
    return {g.scenario.id: g.ok for g in graded if g.recorded}


def load_baseline(path: Path = BASELINE) -> dict[str, bool]:
    """Read the accepted results.

    Args:
        path: The file.

    Returns:
        Whether each scenario passed when last accepted.
    """
    if not path.exists():
        return {}
    baseline: dict[str, bool] = json.loads(path.read_text(encoding="utf-8"))
    return baseline


def save_baseline(results: dict[str, bool], path: Path = BASELINE) -> None:
    """Accept results as the new baseline.

    Args:
        results: The results.
        path: The file.
    """
    text = json.dumps(results, indent=2, sort_keys=True) + "\n"
    path.write_text(text, encoding="utf-8", newline="\n")


def find_problems(
    graded: list[Graded], baseline: dict[str, bool], report: str, expected_report: str
) -> list[str]:
    """Find every reason why CI must fail.

    Args:
        graded: The graded bank.
        baseline: The accepted results.
        report: The committed report.
        expected_report: The report generated from the recordings.

    Returns:
        One sentence per problem; empty if everything is in order.
    """
    problems = []
    by_id = {g.scenario.id: g for g in graded}
    for g in graded:
        if g.stale:
            problems.append(
                f"{g.scenario.id}: its recording is stale, because the prompts, the schema, the model "
                "or the scenario changed. Record it again."
            )
    for scenario_id, was_ok in sorted(baseline.items()):
        current = by_id.get(scenario_id)
        if current is None or not current.recorded:
            problems.append(f"{scenario_id}: it is in the baseline, but its recording is gone.")
        elif was_ok and not current.ok:
            failing = ", ".join(c.name for c in current.checks if not c.passed)
            problems.append(f"{scenario_id}: it passed before and now fails: {failing}.")
    for g in graded:
        if g.recorded and g.scenario.id not in baseline:
            problems.append(f"{g.scenario.id}: it has a recording but is not in the baseline.")
    if report != expected_report:
        problems.append("evals/REPORT.md is out of date. Generate it again.")
    return problems


def _percent(part: int, whole: int) -> str:
    return f"{100 * part / whole:.0f}%" if whole else "n/a"


def render_report(graded: list[Graded]) -> str:
    """Write the public report. Failures are listed with the same prominence as successes.

    Args:
        graded: The graded bank.

    Returns:
        The report, in Markdown.
    """
    recorded = [g for g in graded if g.recorded]
    ok = [g for g in recorded if g.ok]
    lines = [
        "# Evaluation report",
        "",
        "Generated from the recordings in `evals/recordings`. CI fails if this file is out of date.",
        "",
        f"**{len(ok)} of {len(recorded)} recorded scenarios pass** "
        f"({_percent(len(ok), len(recorded))}), out of {len(graded)} in the bank.",
        "",
        "Each scenario has run once, so these numbers do not separate real failures from chance. "
        "The graders check facts in the core and in the text. They cannot judge tone or helpfulness.",
        "",
        "## By category",
        "",
        "| Category | Pass | Recorded | In the bank |",
        "| --- | --- | --- | --- |",
    ]
    for category in sorted({g.scenario.category for g in graded}):
        members = [g for g in graded if g.scenario.category == category]
        done = [g for g in members if g.recorded]
        good = [g for g in done if g.ok]
        lines.append(f"| {category} | {len(good)} | {len(done)} | {len(members)} |")

    lines += ["", "## Failures", ""]
    failures = [g for g in recorded if not g.ok]
    if not failures:
        lines.append("None among the recorded scenarios.")
    for g in failures:
        lines.append(f"- **{g.scenario.id}**")
        lines.extend(f"  - `{c.name}`: {c.detail}" for c in g.checks if not c.passed)

    lines += ["", "## Cost and speed", ""]
    if recorded:
        usages = [g.recording.usage for g in recorded if g.recording is not None]
        agent_calls = sum(u.agent_calls for u in usages)
        simulator_calls = sum(u.simulator_calls for u in usages)
        seconds = sorted(s for u in usages for s in u.agent_call_seconds)
        cost = sum(u.estimated_cost_usd for u in usages)
        lines += [
            "No money is spent, as this runs on a Claude subscription. The cost is what the CLI "
            "estimates the same calls would cost on the API.",
            "",
            f"- Model calls: {agent_calls} by the agent and {simulator_calls} by the simulated patient.",
            f"- Estimated API-equivalent cost of the whole run: ${cost:.4f}.",
        ]
        if seconds:
            lines.append(
                f"- Agent call latency through the CLI: median {statistics.median(seconds):.1f} s, "
                f"slowest {seconds[-1]:.1f} s, over {len(seconds)} calls."
            )
    else:
        lines.append("Nothing has been recorded yet.")

    pending = [g.scenario.id for g in graded if not g.recorded]
    if pending:
        lines += ["", f"## Not recorded yet ({len(pending)})", ""]
        lines.extend(f"- {scenario_id}" for scenario_id in pending)
    return "\n".join(lines) + "\n"


def current_report(path: Path = REPORT) -> str:
    """Read the committed report.

    Args:
        path: The file.

    Returns:
        Its text, or an empty string if there is none.
    """
    return path.read_text(encoding="utf-8").replace("\r\n", "\n") if path.exists() else ""
