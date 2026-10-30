"""Access to the model.

The agent reads untrusted text, so the model must never be able to act on the machine it runs on.
Everything here serves that: it can only return one JSON document, which the code then judges.
See docs/adr/0008 and docs/adr/0009.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import json
import os
import shutil
import subprocess
import tempfile
import threading
import time
import weakref
from collections.abc import Callable
from dataclasses import dataclass
from datetime import date
from typing import Any, Protocol

# The only variables the CLI receives. An allowlist, because a stray ANTHROPIC_API_KEY would be used
# without asking and billed to its owner, and Turno's own tokens are none of the CLI's business.
_ALLOWED_ENV = (
    "APPDATA",
    "HOME",
    "HOMEDRIVE",
    "HOMEPATH",
    "LOCALAPPDATA",
    "PATH",
    "PROGRAMDATA",
    "SYSTEMDRIVE",
    "SYSTEMROOT",
    "TEMP",
    "TMP",
    "USERPROFILE",
)
_TIMEOUT_SECONDS = 120


class LlmError(Exception):
    """The model could not produce a usable answer. The message never includes model output."""


class LlmLimitReachedError(LlmError):
    """The daily limit on model calls has been reached."""


@dataclass(frozen=True)
class Completion:
    """A structured answer from the model.

    Attributes:
        output: The JSON document returned by the model. It is untrusted until validated.
        input_tokens: Tokens read, including cached ones.
        output_tokens: Tokens written.
        estimated_cost_usd: What this would cost on the API. No money is actually spent.
        model: The model that answered.
        seconds: How long the call took.
    """

    output: dict[str, Any]
    input_tokens: int = 0
    output_tokens: int = 0
    estimated_cost_usd: float = 0.0
    model: str = ""
    seconds: float = 0.0


class LlmClient(Protocol):
    """Anything able to answer a prompt with a JSON document that follows a schema."""

    def complete(self, system: str, prompt: str, schema: dict[str, Any]) -> Completion:
        """Ask the model.

        Args:
            system: The instructions, which replace any default ones.
            prompt: The conversation so far.
            schema: The JSON Schema the answer must follow.

        Returns:
            The answer.

        Raises:
            LlmError: If there is no usable answer.
        """
        ...


class ClaudeCliClient:
    """Calls the official, unmodified ``claude -p`` with its owner's own login.

    Every tool, setting, hook, MCP server and command is turned off, the session is not saved, and
    it runs in an empty folder. ``--bare`` would be the natural choice, but it ignores subscription
    logins. ``tests/live/test_isolation.py`` attacks this setup for real.
    """

    def __init__(self, executable: str = "claude", model: str = "haiku") -> None:
        """Create the client.

        Args:
            executable: Name or path of the CLI.
            model: The model to ask for.

        Raises:
            LlmError: If the CLI is not installed.
        """
        resolved = shutil.which(executable)
        if resolved is None:
            raise LlmError("The Claude Code CLI is not installed")
        self._executable = resolved
        self._model = model
        self.sandbox = tempfile.mkdtemp(prefix="turno-llm-")
        weakref.finalize(self, shutil.rmtree, self.sandbox, ignore_errors=True)

    def command(self, system: str, schema: dict[str, Any]) -> list[str]:
        """Build the command line.

        Args:
            system: The instructions.
            schema: The schema of the answer.

        Returns:
            The arguments. The prompt is not among them: it travels through standard input.
        """
        return [
            self._executable,
            "-p",
            "--model",
            self._model,
            "--tools",
            "",
            "--setting-sources",
            "",
            "--strict-mcp-config",
            "--mcp-config",
            '{"mcpServers":{}}',
            "--disable-slash-commands",
            "--no-session-persistence",
            "--system-prompt",
            system,
            "--json-schema",
            json.dumps(schema),
            "--output-format",
            "json",
        ]

    def complete(self, system: str, prompt: str, schema: dict[str, Any]) -> Completion:
        """Ask the model through the CLI.

        Args:
            system: The instructions.
            prompt: The conversation so far.
            schema: The schema of the answer.

        Returns:
            The answer.

        Raises:
            LlmError: If the CLI fails, times out or returns nothing usable.
        """
        started = time.monotonic()
        try:
            process = subprocess.run(  # noqa: S603 - no shell, and the arguments are fixed above
                self.command(system, schema),
                input=prompt,
                cwd=self.sandbox,
                env=isolated_environment(os.environ),
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=_TIMEOUT_SECONDS,
                check=False,
            )
        except subprocess.TimeoutExpired as error:
            raise LlmError("The model took too long") from error
        except OSError as error:
            raise LlmError("The Claude Code CLI could not be started") from error
        if process.returncode != 0:
            raise LlmError(f"The Claude Code CLI failed with exit code {process.returncode}")
        return parse_cli_output(process.stdout, time.monotonic() - started)


def isolated_environment(source: dict[str, str] | os._Environ[str]) -> dict[str, str]:
    """Reduce an environment to what the CLI needs to run and find its login.

    Args:
        source: The environment of this process.

    Returns:
        The environment to give to the CLI.
    """
    upper = {name.upper(): value for name, value in source.items()}
    return {name: upper[name] for name in _ALLOWED_ENV if name in upper}


def parse_cli_output(stdout: str, seconds: float) -> Completion:
    """Read the JSON result printed by the CLI.

    Args:
        stdout: What the CLI printed.
        seconds: How long the call took.

    Returns:
        The answer.

    Raises:
        LlmError: If the result is an error or has no structured output.
    """
    try:
        result = json.loads(stdout)
    except json.JSONDecodeError as error:
        raise LlmError("The Claude Code CLI did not print JSON") from error
    if not isinstance(result, dict) or result.get("is_error"):
        raise LlmError("The model reported an error")
    output = result.get("structured_output")
    if not isinstance(output, dict):
        raise LlmError("The model returned no structured output")
    usage = result.get("usage") or {}
    models = list((result.get("modelUsage") or {}).keys())
    return Completion(
        output=output,
        input_tokens=int(usage.get("input_tokens", 0))
        + int(usage.get("cache_creation_input_tokens", 0))
        + int(usage.get("cache_read_input_tokens", 0)),
        output_tokens=int(usage.get("output_tokens", 0)),
        estimated_cost_usd=float(result.get("total_cost_usd") or 0.0),
        model=models[0] if models else "",
        seconds=seconds,
    )


class DailyLimitedLlm:
    """Refuses to call the model once a daily number of calls has been reached."""

    def __init__(
        self,
        inner: LlmClient,
        max_calls_per_day: int,
        today: Callable[[], date] = date.today,
    ) -> None:
        """Wrap a client.

        Args:
            inner: The client that does the work.
            max_calls_per_day: How many calls to allow per day.
            today: Provides the current date.
        """
        self._inner = inner
        self._max_calls = max_calls_per_day
        self._today = today
        self._lock = threading.Lock()
        self._day = today()
        self._calls = 0

    def complete(self, system: str, prompt: str, schema: dict[str, Any]) -> Completion:
        """Ask the model, if today's limit allows it.

        Args:
            system: The instructions.
            prompt: The conversation so far.
            schema: The schema of the answer.

        Returns:
            The answer.

        Raises:
            LlmLimitReachedError: If today's limit has been reached.
        """
        with self._lock:
            if self._today() != self._day:
                self._day = self._today()
                self._calls = 0
            if self._calls >= self._max_calls:
                raise LlmLimitReachedError("The daily limit on model calls has been reached")
            self._calls += 1
        return self._inner.complete(system, prompt, schema)


ScriptedOutput = dict[str, Any] | Exception | Callable[[], dict[str, Any]]


class ScriptedLlm:
    """Answers from a list, for tests and CI, which never call a real model."""

    def __init__(self, outputs: list[ScriptedOutput] | None = None) -> None:
        """Create the model.

        Args:
            outputs: The answers to give, in order. An exception is raised instead of returned, and
                a function is called to build an answer that depends on what happened before.
        """
        self.outputs = list(outputs or [])
        self.prompts: list[str] = []
        self.systems: list[str] = []

    def complete(self, system: str, prompt: str, schema: dict[str, Any]) -> Completion:
        """Give the next answer of the list.

        Args:
            system: The instructions, which are recorded.
            prompt: The conversation so far, which is recorded.
            schema: The schema, which is ignored.

        Returns:
            The next answer.

        Raises:
            LlmError: If the list has run out, or if the next entry is an exception.
        """
        self.systems.append(system)
        self.prompts.append(prompt)
        if not self.outputs:
            raise LlmError("The script has run out of answers")
        next_output = self.outputs.pop(0)
        if isinstance(next_output, Exception):
            raise next_output
        if callable(next_output):
            next_output = next_output()
        return Completion(output=next_output, model="scripted")
