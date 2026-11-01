"""The page and the socket of the voice channel.

A browser sends the origin of whichever page opened a socket, and no same-origin rule protects a
socket. So the origin is checked by hand, before anything else.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import asyncio
import json
import logging
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from turno_agents.agent import AgentService
from turno_agents.conversation import ConversationStore
from turno_agents.core_client import CoreApi, CoreRefusalError, CoreUnavailableError
from turno_agents.limits import WindowLimiter
from turno_agents.voice.engines import VoiceEngines
from turno_agents.voice.session import VoiceSession

LOG = logging.getLogger(__name__)

_STATIC = Path(__file__).parent.parent / "static"
# About a quarter of a second of audio: anything larger is not what the page sends.
MAX_FRAME_BYTES = 8192
MAX_CALL_SECONDS = 600
# Each call holds a model in use on the CPU, so few can run at once.
MAX_CALLS = 2
# Close codes, in the range left for applications.
_BAD_ORIGIN = 4403
_BUSY = 4429
_BAD_START = 4400
_TOO_LONG = 4408
_TOO_BIG = 1009


class _Start(BaseModel):
    """Who is calling. A real line would provide the number itself."""

    model_config = ConfigDict(extra="forbid")

    phone: str = Field(pattern=r"^\+[1-9][0-9]{6,14}$")
    full_name: str | None = Field(default=None, min_length=1, max_length=200)
    language: str = Field(default="es", pattern=r"^[a-z]{2}$")


class _SocketSink:
    """Sends from the session's threads through the socket, which lives in the event loop."""

    def __init__(self, socket: WebSocket, loop: asyncio.AbstractEventLoop) -> None:
        self._socket = socket
        self._loop = loop

    def event(self, payload: dict[str, object]) -> None:
        asyncio.run_coroutine_threadsafe(
            self._socket.send_text(json.dumps(payload)), self._loop
        ).result()

    def audio(self, samples: bytes) -> None:
        asyncio.run_coroutine_threadsafe(self._socket.send_bytes(samples), self._loop).result()


def is_same_origin(origin: str | None, host: str | None) -> bool:
    """Tell whether a socket was opened by a page of this service.

    Args:
        origin: The Origin header, which a browser always sends and a page cannot change.
        host: The Host header.

    Returns:
        Whether they match. With no origin it was not a browser's page, and is refused all the same.
    """
    if not origin or not host:
        return False
    parsed = urlparse(origin)
    return parsed.scheme in {"http", "https"} and parsed.netloc == host


def add_voice(
    app: FastAPI,
    agent: AgentService,
    core: CoreApi,
    store: ConversationStore,
    engines: Callable[[], VoiceEngines],
    messages: WindowLimiter,
    phones: WindowLimiter,
    starts: WindowLimiter,
    max_call_seconds: float = MAX_CALL_SECONDS,
) -> None:
    """Add the page and the socket to the application.

    Args:
        app: The application.
        agent: The agent.
        core: Access to the core.
        store: Where conversations are kept.
        engines: Provides the engines, which take some seconds to load.
        messages: The limit of messages per conversation.
        phones: The limit of conversations per phone.
        starts: The limit of conversations in total.
        max_call_seconds: How long a call can last.
    """
    calls = 0

    @app.get("/voice", include_in_schema=False)
    def voice_page() -> FileResponse:
        """Serve the page.

        Returns:
            The page.
        """
        return FileResponse(_STATIC / "voice.html")

    async def bound_patient(body: _Start) -> dict[str, Any]:
        def find() -> dict[str, Any]:
            try:
                return core.find_patient(body.phone)
            except CoreRefusalError as error:
                if error.code != "PATIENT_NOT_FOUND" or body.full_name is None:
                    raise
            return core.register_patient(body.full_name, body.phone, body.language)

        return await asyncio.to_thread(find)

    @app.websocket("/ws/voice")
    async def voice_socket(socket: WebSocket) -> None:
        """Hold a call.

        Args:
            socket: The connection.
        """
        nonlocal calls
        if not is_same_origin(socket.headers.get("origin"), socket.headers.get("host")):
            await socket.close(code=_BAD_ORIGIN)
            return
        if calls >= MAX_CALLS:
            await socket.close(code=_BUSY)
            return
        calls += 1
        session: VoiceSession | None = None
        try:
            await socket.accept()
            try:
                body = _Start.model_validate_json(await socket.receive_text())
            except (ValidationError, ValueError, KeyError):
                await socket.close(code=_BAD_START)
                return
            if not phones.allow(body.phone) or not starts.allow("all"):
                await socket.close(code=_BUSY)
                return
            try:
                patient = await bound_patient(body)
            except (CoreRefusalError, CoreUnavailableError):
                await socket.close(code=_BAD_START)
                return
            conversation = store.create(patient["id"], patient["language"])
            loaded = await asyncio.to_thread(engines)
            sink = _SocketSink(socket, asyncio.get_running_loop())
            session = VoiceSession(
                agent, conversation, loaded, sink, lambda: messages.allow(conversation.id)
            )
            await socket.send_text(
                json.dumps(
                    {"type": "ready", "output_sample_rate": loaded.text_to_speech.sample_rate}
                )
            )
            deadline = time.monotonic() + max_call_seconds
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    await socket.close(code=_TOO_LONG)
                    return
                try:
                    message = await asyncio.wait_for(socket.receive(), timeout=remaining)
                except TimeoutError:
                    await socket.close(code=_TOO_LONG)
                    return
                if message["type"] == "websocket.disconnect":
                    return
                data = message.get("bytes")
                if data is None:
                    continue
                if len(data) > MAX_FRAME_BYTES:
                    await socket.close(code=_TOO_BIG)
                    return
                session.feed(data)
        except WebSocketDisconnect:
            return
        finally:
            calls -= 1
            if session is not None:
                session.close()
