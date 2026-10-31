"""Runs the attacks against the running stack, and writes or checks the report.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import argparse
import os
import sys
import time
from pathlib import Path

from security.agent_attacks import agent_attacks
from security.bank import Attack, Stack
from security.core_attacks import core_attacks, flood_of_guesses, flood_of_requests
from security.report import Result, render, run_attack

REPORT = Path(__file__).parent / "REPORT.md"
_WINDOW_SECONDS = 60
LIMITS = "Floods"


def _limit_attacks() -> list[Attack]:
    return [
        # In this order: guessing blocks the address, so a refusal after it would prove nothing.
        Attack("limit-01", LIMITS, "Ask more than a clinic ever would", flood_of_requests),
        Attack("limit-02", LIMITS, "Guess tokens until one works", flood_of_guesses),
    ]


def _wait_for_a_new_window() -> None:
    """Start clear of any limit: other tests may have just used this minute."""
    time.sleep(_WINDOW_SECONDS - time.time() % _WINDOW_SECONDS + 1)


def run_all(stack: Stack) -> list[Result]:
    """Run every attack, the ones that use up a limit last and in a minute of their own.

    Args:
        stack: The stack.

    Returns:
        What happened with each.
    """
    _wait_for_a_new_window()
    results = [run_attack(attack, stack) for attack in [*core_attacks(), *agent_attacks()]]
    _wait_for_a_new_window()
    results.extend(run_attack(attack, stack) for attack in _limit_attacks())
    return results


def main() -> int:
    """Run the bank.

    Returns:
        The exit code: not zero on a breach, or if the report is not what was committed.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="compare with the committed report")
    args = parser.parse_args()

    stack = Stack(
        os.environ.get("TURNO_CORE_URL", "http://127.0.0.1:8080"),
        os.environ.get("TURNO_CONSOLE_URL", "http://127.0.0.1:4200"),
        {
            "agents": os.environ["TURNO_API_TOKEN_AGENTS"],
            "staff": os.environ["TURNO_API_TOKEN_STAFF"],
            "workflows": os.environ["TURNO_API_TOKEN_WORKFLOWS"],
        },
    )
    results = run_all(stack)
    breaches = [result for result in results if result.stopper is None]
    for result in breaches:
        print(f"BREACH {result.attack.id}: {result.attack.goal}: {result.note}", file=sys.stderr)
    print(f"{len(results) - len(breaches)} of {len(results)} attacks stopped")
    report = render(results)
    if args.check:
        if not REPORT.exists() or REPORT.read_text(encoding="utf-8") != report:
            print(
                "The report is not the committed one: run without --check and review it",
                file=sys.stderr,
            )
            return 1
    elif not breaches:
        REPORT.write_text(report, encoding="utf-8", newline="\n")
    return 1 if breaches else 0


if __name__ == "__main__":
    sys.exit(main())
