"""Command line of the evaluations: ``python -m evals <run|gate|report|accept|list>``.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import argparse
import os
import sys
from pathlib import Path

from evals import judge, matrix
from evals.gate import (
    BASELINE,
    REPORT,
    baseline_of,
    current_report,
    find_problems,
    grade_bank,
    load_baseline,
    render_report,
    save_baseline,
)
from evals.graders import grade, passed
from evals.recording import RECORDINGS, UnsafeRecordingError, load_recording, save_recording
from evals.runner import EvaluationAborted, run_scenario, scenario_fingerprint
from evals.scenarios import Scenario, load_bank
from turno_agents.core_client import HttpCoreClient
from turno_agents.llm import ClaudeCliClient

DEFAULT_MODEL = "haiku"


def _select(
    args: argparse.Namespace, bank: list[Scenario], folder: Path = RECORDINGS
) -> list[Scenario]:
    chosen = bank
    if args.only:
        wanted = set(args.only.split(","))
        unknown = wanted - {s.id for s in bank}
        if unknown:
            raise SystemExit(f"Unknown scenarios: {sorted(unknown)}")
        chosen = [s for s in chosen if s.id in wanted]
    if args.category:
        chosen = [s for s in chosen if s.category == args.category]
    if not args.force:
        chosen = [s for s in chosen if _needs_recording(s, args.model, folder)]
    return chosen[: args.limit] if args.limit else chosen


def _needs_recording(scenario: Scenario, model: str, folder: Path = RECORDINGS) -> bool:
    recording = load_recording(scenario.id, folder)
    return recording is None or recording.fingerprint != scenario_fingerprint(scenario, model)


def _run(args: argparse.Namespace, folder: Path = RECORDINGS) -> int:
    url = os.environ.get("TURNO_CORE_URL", "http://127.0.0.1:8080")
    token = os.environ.get("TURNO_API_TOKEN_AGENTS")
    if not token:
        print("Set TURNO_API_TOKEN_AGENTS, for example with: uv run --env-file ../.env")
        return 2
    selected = _select(args, load_bank(), folder)
    print(f"{len(selected)} scenarios to record")
    core = HttpCoreClient(url, token)
    executable = os.environ.get("TURNO_CLAUDE_EXECUTABLE", "claude")
    agent_llm = ClaudeCliClient(executable, args.model)
    simulator_llm = ClaudeCliClient(executable, args.simulator_model)
    calls = 0
    try:
        for scenario in selected:
            if args.max_calls and calls >= args.max_calls:
                print(f"Stopped at the limit of {args.max_calls} calls")
                break
            try:
                recording = run_scenario(scenario, core, agent_llm, simulator_llm, args.model)
                save_recording(recording, folder)
            except (EvaluationAborted, UnsafeRecordingError) as error:
                print(f"ABORTED at {scenario.id}: {error}. Nothing was recorded for it.")
                return 3
            usage = recording.usage
            calls += usage.agent_calls + usage.simulator_calls
            checks = grade(scenario, recording)
            state = "PASS" if passed(checks) else "FAIL"
            print(
                f"{state} {scenario.id}: {usage.agent_calls}+{usage.simulator_calls} calls, "
                f"{usage.input_tokens}+{usage.output_tokens} tokens"
            )
            for check in checks:
                if not check.passed:
                    print(f"     {check.name}: {check.detail}")
    finally:
        core.close()
    print(f"Calls used: {calls}. Run `python -m evals report` and review before accepting.")
    return 0


def _matrix(args: argparse.Namespace) -> int:
    for model in args.models.split(","):
        args.model = model
        print(f"--- {model}")
        folder = matrix.folder_of(model)
        folder.mkdir(parents=True, exist_ok=True)
        code = _run(args, folder)
        if code != 0:
            return code
    matrix.REPORT.write_text(matrix.render_report(load_bank()), encoding="utf-8", newline="\n")
    return 0


def _calibrate(args: argparse.Namespace) -> int:
    cases = judge.load_labels()
    executable = os.environ.get("TURNO_CLAUDE_EXECUTABLE", "claude")
    llm = ClaudeCliClient(executable, args.model)
    try:
        calibration = judge.calibrate(llm, cases, args.model, args.repeats)
    except judge.JudgeError as error:
        print(f"ABORTED: {error}. Nothing was recorded.")
        return 3
    judge.save_calibration(calibration)
    judge.REPORT.write_text(judge.render_report(calibration, cases), encoding="utf-8", newline="\n")
    agreement = judge.agreement_of(calibration, cases)
    print(f"Recall {agreement.recall:.2f}, kappa {agreement.kappa:.2f}. See {judge.REPORT.name}.")
    return 0


def _review(args: argparse.Namespace) -> int:
    calibration = judge.load_calibration()
    cases = judge.load_labels()
    if calibration is None or not judge.is_current(calibration, cases):
        print("The judge is not calibrated, so its findings would mean nothing.")
        return 2
    questions = judge.trusted_questions(calibration, cases)
    if not questions:
        print("The judge did not reach the thresholds, so its findings are not worth reading.")
        return 2
    executable = os.environ.get("TURNO_CLAUDE_EXECUTABLE", "claude")
    llm = ClaudeCliClient(executable, calibration.model)
    findings = 0
    for scenario in load_bank():
        recording = load_recording(scenario.id)
        if recording is None or (args.category and scenario.category != args.category):
            continue
        for criterion in questions:
            try:
                violated = judge.ask(
                    llm, criterion, judge.turns_of(recording), judge.actions_of(recording)
                )
            except judge.JudgeError as error:
                print(f"NO ANSWER {scenario.id} {criterion}: {error}")
                continue
            if violated:
                findings += 1
                print(f"TO READ {scenario.id}: {criterion}")
    print(f"{findings} findings for a person to read. They change nothing by themselves.")
    return 0


def _gate(_: argparse.Namespace) -> int:
    graded = grade_bank(load_bank())
    problems = find_problems(graded, load_baseline(), current_report(), render_report(graded))
    problems.extend(_stale_reports())
    recorded = sum(g.recorded for g in graded)
    good = sum(g.ok for g in graded)
    print(f"{good} of {recorded} recorded scenarios pass, out of {len(graded)} in the bank")
    for problem in problems:
        print(f"GATE: {problem}")
    return 1 if problems else 0


def _judge_report_text() -> str:
    return judge.render_report(judge.load_calibration(), judge.load_labels())


def _stale_reports() -> list[str]:
    expected = {
        judge.REPORT: _judge_report_text(),
        matrix.REPORT: matrix.render_report(load_bank()),
    }
    return [
        f"{path.name} is not up to date: run `python -m evals report`"
        for path, text in expected.items()
        if not path.exists() or path.read_text(encoding="utf-8") != text
    ]


def _report(_: argparse.Namespace) -> int:
    judge.REPORT.write_text(_judge_report_text(), encoding="utf-8", newline="\n")
    matrix.REPORT.write_text(matrix.render_report(load_bank()), encoding="utf-8", newline="\n")
    REPORT.write_text(render_report(grade_bank(load_bank())), encoding="utf-8", newline="\n")
    print(f"Wrote {REPORT.name}")
    return 0


def _accept(args: argparse.Namespace) -> int:
    graded = grade_bank(load_bank())
    stale = [g.scenario.id for g in graded if g.stale]
    if stale:
        print(f"Cannot accept stale recordings: {stale}")
        return 1
    baseline = load_baseline()
    worse = sorted(
        g.scenario.id for g in graded if baseline.get(g.scenario.id) and g.recorded and not g.ok
    )
    if worse and not args.accept_regressions:
        print(
            f"These passed before and now fail: {worse}. Use --accept-regressions to accept them."
        )
        return 1
    save_baseline(baseline_of(graded), BASELINE)
    print(f"Accepted {sum(g.recorded for g in graded)} recorded scenarios")
    return 0


def _list(_: argparse.Namespace) -> int:
    for g in grade_bank(load_bank()):
        state = "-" if not g.recorded else ("stale" if g.stale else ("pass" if g.ok else "FAIL"))
        print(f"{state:>5}  {g.scenario.category:<13} {g.scenario.id}")
    return 0


def main() -> int:
    """Run the command line.

    Returns:
        The exit code.
    """
    parser = argparse.ArgumentParser(prog="python -m evals")
    commands = parser.add_subparsers(dest="command", required=True)
    run = commands.add_parser("run", help="Record scenarios. Spends the Claude plan.")
    run.add_argument("--only", help="Comma-separated scenario identifiers")
    run.add_argument("--category")
    run.add_argument("--limit", type=int, help="How many scenarios at most")
    run.add_argument("--max-calls", type=int, help="Stop starting scenarios after this many calls")
    run.add_argument("--force", action="store_true", help="Record even if the recording is current")
    run.add_argument("--model", default=DEFAULT_MODEL)
    run.add_argument("--simulator-model", default=DEFAULT_MODEL)
    run.set_defaults(handler=_run)
    grid = commands.add_parser("matrix", help="Record with several models. Spends the Claude plan.")
    grid.add_argument("--models", default="haiku,sonnet,opus", help="Comma-separated models")
    grid.add_argument("--only")
    grid.add_argument("--category")
    grid.add_argument("--limit", type=int, help="How many scenarios at most, per model")
    grid.add_argument("--max-calls", type=int, help="Stop starting scenarios, per model")
    grid.add_argument("--force", action="store_true")
    grid.add_argument("--simulator-model", default=DEFAULT_MODEL)
    grid.set_defaults(handler=_matrix)
    calibrate = commands.add_parser(
        "judge-calibrate", help="Measure the judge against the labels. Spends the Claude plan."
    )
    calibrate.add_argument("--model", default=DEFAULT_MODEL)
    calibrate.add_argument("--repeats", type=int, default=3, help="Answers per conversation")
    calibrate.set_defaults(handler=_calibrate)
    review = commands.add_parser(
        "judge-review", help="List recordings a person should read. Spends the Claude plan."
    )
    review.add_argument("--category")
    review.set_defaults(handler=_review)
    commands.add_parser("gate", help="What CI runs: no model and no core.").set_defaults(
        handler=_gate
    )
    commands.add_parser("report", help="Generate the report.").set_defaults(handler=_report)
    accept = commands.add_parser("accept", help="Accept the results as the new baseline.")
    accept.add_argument("--accept-regressions", action="store_true")
    accept.set_defaults(handler=_accept)
    commands.add_parser("list", help="Show the state of each scenario.").set_defaults(handler=_list)
    args = parser.parse_args()
    code: int = args.handler(args)
    return code


if __name__ == "__main__":
    sys.exit(main())
