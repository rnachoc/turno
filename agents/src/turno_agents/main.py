"""HTTP entry point of the agents service.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import threading
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated, Any

from fastapi import Depends, FastAPI, HTTPException, Request, Response
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ConfigDict, Field
from starlette.middleware.trustedhost import TrustedHostMiddleware

from turno_agents.activity import ActivityReporter
from turno_agents.agent import AgentService
from turno_agents.channels.gateway import Gateway, SimulatedGateway
from turno_agents.channels.service import ChannelService, NoticeSender
from turno_agents.channels.webhooks import add_webhooks
from turno_agents.conversation import Conversation, ConversationStore, trace_view
from turno_agents.core_client import (
    CoreApi,
    CoreRefusalError,
    CoreUnavailableError,
    HttpCoreClient,
)
from turno_agents.guards import Triage, triage
from turno_agents.limits import WindowLimiter
from turno_agents.llm import ClaudeCliClient, DailyLimitedLlm, LlmClient
from turno_agents.settings import Settings
from turno_agents.voice.engines import VoiceEngines, load_engines
from turno_agents.voice.routes import add_voice

# Comfortably more than the longest valid message, once in JSON and with every character escaped.
MAX_BODY_BYTES = 16 * 1024
_STATIC = Path(__file__).parent / "static"
_SECURITY_HEADERS = {
    "Content-Security-Policy": "default-src 'self'; frame-ancestors 'none'; base-uri 'none'",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
}


class StartRequest(BaseModel):
    """Who the conversation is with. A real channel would provide the number itself."""

    model_config = ConfigDict(extra="forbid")

    phone: str = Field(pattern=r"^\+[1-9][0-9]{6,14}$")
    full_name: str | None = Field(default=None, min_length=1, max_length=200)
    language: str = Field(default="es", pattern=r"^[a-z]{2}$")


class MessageRequest(BaseModel):
    """A patient's message."""

    model_config = ConfigDict(extra="forbid")

    text: str = Field(min_length=1, max_length=1000)


def _size_refusal(request: Request) -> Response | None:
    """Refuse a request that is too large, or whose length is unknown and so could be any size."""
    if request.method not in {"POST", "PUT", "PATCH"}:
        return None
    declared = request.headers.get("content-length")
    if declared is None or not declared.isdigit():
        return JSONResponse({"detail": {"code": "LENGTH_REQUIRED"}}, status_code=411)
    if int(declared) > MAX_BODY_BYTES:
        return JSONResponse({"detail": {"code": "REQUEST_TOO_LARGE"}}, status_code=413)
    return None


def _problem(status: int, code: str) -> HTTPException:
    return HTTPException(status_code=status, detail={"code": code})


def create_app(
    settings: Settings | None = None,
    llm: LlmClient | None = None,
    core: CoreApi | None = None,
    store: ConversationStore | None = None,
    gateway: Gateway | None = None,
    voice_engines: VoiceEngines | None = None,
) -> FastAPI:
    """Build the FastAPI application.

    The interactive docs and the OpenAPI schema stay off because this is an internal service and
    nothing should describe its surface to an anonymous caller.

    Args:
        settings: The configuration, read from the environment if not given.
        llm: Access to the model, built from the settings if not given.
        core: Access to the core, built from the settings if not given.
        store: Where conversations are kept, a new one if not given.
        gateway: How messages go out on the channels, a simulated one if not given.
        voice_engines: What listens and speaks, loaded on the first call if not given.

    Returns:
        The configured application.
    """
    config = settings or Settings()
    if core is None and config.api_token_agents is not None:
        core = HttpCoreClient(config.core_url, config.api_token_agents.get_secret_value())
    if llm is None and config.llm_backend == "claude-cli":
        cli = ClaudeCliClient(config.claude_executable, config.llm_model)
        llm = DailyLimitedLlm(cli, config.max_llm_calls_per_day)

    conversations = store or ConversationStore()
    agent = AgentService(llm, core) if llm is not None and core is not None else None
    messages = WindowLimiter(config.max_messages_per_conversation_per_minute, 60)
    phones = WindowLimiter(config.max_conversations_per_phone_per_hour, 3600)
    starts = WindowLimiter(config.max_conversations_per_minute, 60)

    channels = None
    if agent is not None and core is not None and config.channel_webhook_secret is not None:
        channels = ChannelService(
            agent,
            core,
            conversations,
            gateway or SimulatedGateway(config.channel_outbox),
            WindowLimiter(config.max_messages_per_conversation_per_minute, 60),
        )

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        reporter = ActivityReporter(conversations, core) if core is not None else None
        if reporter is not None:
            reporter.start()
        if agent is not None:
            threading.Thread(target=agent.warm_up, name="warm-up", daemon=True).start()
        sender = NoticeSender(channels) if channels is not None else None
        if sender is not None:
            sender.start()
        yield
        if sender is not None:
            sender.stop()
        if reporter is not None:
            reporter.stop()

    app = FastAPI(
        title="Turno Agents", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan
    )
    app.add_middleware(TrustedHostMiddleware, allowed_hosts=config.allowed_hosts)

    @app.middleware("http")
    async def harden(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        """Refuse oversized requests and add the security headers to every response."""
        refusal = _size_refusal(request)
        response = refusal if refusal is not None else await call_next(request)
        response.headers.update(_SECURITY_HEADERS)
        return response

    if config.voice_enabled and agent is not None and core is not None:
        add_voice(
            app,
            agent,
            core,
            conversations,
            _engines_once(voice_engines, config),
            messages,
            phones,
            starts,
        )

    if channels is not None and config.channel_webhook_secret is not None:
        add_webhooks(app, channels, config.channel_webhook_secret.get_secret_value())

    def conversation_of(conversation_id: str) -> Conversation:
        conversation = conversations.get(conversation_id)
        if conversation is None:
            raise _problem(404, "CONVERSATION_NOT_FOUND")
        return conversation

    current = Annotated[Conversation, Depends(conversation_of)]

    @app.get("/health")
    def health() -> dict[str, str]:
        """Report that the process is able to serve requests.

        Returns:
            The service status, in the same shape as the core's health endpoint.
        """
        return {"status": "UP"}

    @app.get("/", include_in_schema=False)
    def chat_page() -> FileResponse:
        """Serve the test chat.

        Returns:
            The page.
        """
        return FileResponse(_STATIC / "chat.html")

    @app.post("/api/conversations", status_code=201)
    def start(body: StartRequest) -> dict[str, str]:
        """Start a conversation, binding it to a patient once and for all.

        Args:
            body: Who the conversation is with.

        Returns:
            The conversation, without the patient's identifier.
        """
        if agent is None or core is None:
            raise _problem(503, "MODEL_NOT_CONFIGURED")
        # Before the core: each new phone would register a patient.
        if not phones.allow(body.phone):
            raise _too_many("TOO_MANY_CONVERSATIONS", phones)
        if not starts.allow("all"):
            raise _too_many("TOO_MANY_CONVERSATIONS", starts)
        try:
            patient = _find_or_register(core, body)
        except CoreRefusalError as error:
            raise _problem(error.status, error.code) from error
        except CoreUnavailableError as error:
            raise _problem(503, "CORE_UNAVAILABLE") from error
        conversation = conversations.create(patient["id"], patient["language"])
        return {"conversation_id": conversation.id, "language": conversation.language}

    @app.post("/api/conversations/{conversation_id}/messages")
    def send(conversation: current, body: MessageRequest) -> dict[str, str]:
        """Answer a patient's message.

        Args:
            conversation: The conversation.
            body: The message.

        Returns:
            The reply and the state of the conversation.
        """
        if agent is None:
            raise _problem(503, "MODEL_NOT_CONFIGURED")
        # No limit may stand between someone and the emergency number.
        emergency = triage(body.text) is Triage.EMERGENCY
        if not messages.allow(conversation.id) and not emergency:
            raise _too_many("TOO_MANY_MESSAGES", messages)
        if not conversation.lock.acquire(blocking=False):
            if emergency:
                reply = agent.answer_emergency(conversation)
                return {"reply": reply, "state": conversation.state.value}
            raise _problem(409, "CONVERSATION_BUSY")
        try:
            reply = agent.handle(conversation, body.text)
        finally:
            conversation.lock.release()
        return {"reply": reply, "state": conversation.state.value}

    @app.get("/api/conversations/{conversation_id}/trace")
    def trace(conversation: current) -> dict[str, Any]:
        """Show what happened in a conversation. It holds codes and usage, never patient text.

        Args:
            conversation: The conversation.

        Returns:
            The state and the steps.
        """
        return {"state": conversation.state.value, "steps": trace_view(conversation)}

    app.mount("/static", StaticFiles(directory=_STATIC), name="static")
    return app


def _engines_once(given: VoiceEngines | None, config: Settings) -> Callable[[], VoiceEngines]:
    """Load the engines on the first call, since they take seconds and most never need them."""
    lock = threading.Lock()
    loaded = [given] if given is not None else []

    def engines() -> VoiceEngines:
        with lock:
            if not loaded:
                loaded.append(load_engines(config.voice_models, config.voice_whisper_model))
            return loaded[0]

    return engines


def _too_many(code: str, limiter: WindowLimiter) -> HTTPException:
    return HTTPException(
        status_code=429,
        detail={"code": code},
        headers={"Retry-After": str(limiter.seconds_until_next_window())},
    )


def _find_or_register(core: CoreApi, body: StartRequest) -> dict[str, Any]:
    try:
        return core.find_patient(body.phone)
    except CoreRefusalError as error:
        if error.code != "PATIENT_NOT_FOUND" or body.full_name is None:
            raise
    return core.register_patient(body.full_name, body.phone, body.language)


app = create_app()
