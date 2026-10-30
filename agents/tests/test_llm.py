"""Tests for the model access, none of which call a model.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import json
import sys
from datetime import date
from pathlib import Path

import pytest

from turno_agents.llm import (
    ClaudeCliClient,
    DailyLimitedLlm,
    LlmError,
    LlmLimitReachedError,
    ScriptedLlm,
    isolated_environment,
    parse_cli_output,
)


def _flag_value(command: list[str], flag: str) -> str:
    return command[command.index(flag) + 1]


def _client() -> ClaudeCliClient:
    # Any real executable will do, since these tests only look at the command it would receive.
    return ClaudeCliClient(executable=sys.executable, model="haiku")


def test_every_capability_of_the_cli_is_switched_off() -> None:
    """The command leaves the model with nothing to act with."""
    command = _client().command("instructions", {"type": "object"})

    assert _flag_value(command, "--tools") == ""
    assert _flag_value(command, "--setting-sources") == ""
    assert _flag_value(command, "--mcp-config") == '{"mcpServers":{}}'
    assert "--strict-mcp-config" in command
    assert "--disable-slash-commands" in command
    assert "--no-session-persistence" in command


def test_the_default_instructions_are_replaced_and_not_extended() -> None:
    """The model gets Turno's instructions only."""
    command = _client().command("instructions", {"type": "object"})

    assert _flag_value(command, "--system-prompt") == "instructions"
    assert "--append-system-prompt" not in command


def test_the_answer_must_follow_the_schema() -> None:
    """The schema and the JSON format are always requested."""
    command = _client().command("instructions", {"type": "object"})

    assert json.loads(_flag_value(command, "--json-schema")) == {"type": "object"}
    assert _flag_value(command, "--output-format") == "json"


def test_the_prompt_never_travels_on_the_command_line() -> None:
    """Untrusted text cannot be parsed as a flag, and does not appear in process listings."""
    assert "UNTRUSTED" not in " ".join(_client().command("instructions", {"type": "object"}))


def test_a_missing_cli_is_reported() -> None:
    """A wrong path fails at startup, and not in the middle of a conversation."""
    with pytest.raises(LlmError):
        ClaudeCliClient(executable="no-such-program-for-turno")


def test_the_cli_runs_in_an_empty_folder() -> None:
    """The working folder holds nothing the CLI could load or read."""
    assert list(Path(_client().sandbox).iterdir()) == []


def test_api_keys_and_turno_tokens_do_not_reach_the_cli() -> None:
    """A stray key would be billed without asking, and tokens are none of its business."""
    source = {
        "ANTHROPIC_API_KEY": "secret-1",
        "ANTHROPIC_AUTH_TOKEN": "secret-2",
        "CLAUDE_CODE_OAUTH_TOKEN": "secret-3",
        "TURNO_API_TOKEN_AGENTS": "secret-4",
        "TURNO_DB_PASSWORD": "secret-5",
        "SOME_OTHER_SECRET": "secret-6",
        "PATH": "/usr/bin",
        "HOME": "/home/test",
    }

    assert isolated_environment(source) == {"PATH": "/usr/bin", "HOME": "/home/test"}


def test_the_environment_keeps_what_windows_needs_whatever_its_case() -> None:
    """Windows may spell its variables in mixed case."""
    source = {"Path": "C:\\bin", "SystemRoot": "C:\\Windows", "USERPROFILE": "C:\\Users\\x"}

    assert isolated_environment(source) == {
        "PATH": "C:\\bin",
        "SYSTEMROOT": "C:\\Windows",
        "USERPROFILE": "C:\\Users\\x",
    }


def test_parses_the_result_of_the_cli() -> None:
    """Output and usage are read from the documented fields."""
    stdout = json.dumps(
        {
            "is_error": False,
            "structured_output": {"reply": "hola"},
            "total_cost_usd": 0.5,
            "usage": {
                "input_tokens": 2,
                "cache_creation_input_tokens": 10,
                "cache_read_input_tokens": 5,
                "output_tokens": 7,
            },
            "modelUsage": {"claude-haiku-5-5": {}},
        }
    )

    completion = parse_cli_output(stdout, 1.5)

    assert completion.output == {"reply": "hola"}
    assert completion.input_tokens == 17
    assert completion.output_tokens == 7
    assert completion.estimated_cost_usd == 0.5
    assert completion.model == "claude-haiku-5-5"
    assert completion.seconds == 1.5


@pytest.mark.parametrize(
    "stdout",
    [
        "not json",
        "[]",
        json.dumps({"is_error": True, "structured_output": {"reply": "x"}}),
        json.dumps({"is_error": False}),
        json.dumps({"is_error": False, "structured_output": "text"}),
    ],
)
def test_refuses_results_without_a_usable_answer(stdout: str) -> None:
    """Anything but a structured answer is an error."""
    with pytest.raises(LlmError):
        parse_cli_output(stdout, 1.0)


def test_errors_do_not_repeat_what_the_cli_printed() -> None:
    """Model output could carry patient data, so it stays out of error messages."""
    with pytest.raises(LlmError) as error:
        parse_cli_output("patient secret, not json", 1.0)

    assert "patient secret" not in str(error.value)


def test_the_daily_limit_stops_calls_and_resets_the_next_day() -> None:
    """A runaway loop cannot use up the plan."""
    day = [date(2027, 3, 1)]
    inner = ScriptedLlm([{"n": 1}, {"n": 2}, {"n": 3}])
    limited = DailyLimitedLlm(inner, max_calls_per_day=2, today=lambda: day[0])

    limited.complete("s", "p", {})
    limited.complete("s", "p", {})
    with pytest.raises(LlmLimitReachedError):
        limited.complete("s", "p", {})
    assert len(inner.prompts) == 2

    day[0] = date(2027, 3, 2)
    assert limited.complete("s", "p", {}).output == {"n": 3}


def test_the_scripted_model_raises_when_its_script_runs_out() -> None:
    """A test that makes more calls than expected fails loudly."""
    with pytest.raises(LlmError):
        ScriptedLlm([]).complete("s", "p", {})


def test_the_scripted_model_can_simulate_a_failure() -> None:
    """Failures can be scripted too."""
    with pytest.raises(LlmError, match="boom"):
        ScriptedLlm([LlmError("boom")]).complete("s", "p", {})
