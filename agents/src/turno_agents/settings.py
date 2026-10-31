"""Configuration of the agents service, read from the environment.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

from typing import Literal

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Settings, each read from a variable named ``TURNO_<FIELD>``."""

    model_config = SettingsConfigDict(env_prefix="TURNO_", extra="ignore")

    core_url: str = "http://127.0.0.1:8080"
    api_token_agents: SecretStr | None = None
    api_token_workflows: SecretStr | None = None
    temporal_address: str = "127.0.0.1:7233"

    # "none" serves everything except conversations, which is what the container does: it has no
    # Claude login. See docs/adr/0009.
    llm_backend: Literal["none", "claude-cli"] = "none"
    llm_model: str = "haiku"
    claude_executable: str = "claude"

    # Protects the author's plan from a runaway loop.
    max_llm_calls_per_day: int = 150

    # Rejecting other host names stops a malicious website from reaching this local service through
    # DNS rebinding, and from spending the plan.
    allowed_hosts: list[str] = ["127.0.0.1", "localhost"]
