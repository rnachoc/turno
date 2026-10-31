"""Where the providers deliver messages. Nothing here is believed until its signature checks out.

WhatsApp's payload follows the shape of Meta's Cloud API, and email the usual inbound webhook of a
mail provider, which says whether the sender passed its checks. Only those fields are read.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import hashlib
import hmac
import json
import logging
import time
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from typing import Any

from fastapi import FastAPI, HTTPException, Request

from turno_agents.channels.service import ChannelService, strip_quoted_reply

LOG = logging.getLogger(__name__)

SIGNATURE_HEADER = "X-Hub-Signature-256"
# Older than this and it is a replay, or a delay that makes an answer pointless.
MAX_AGE_SECONDS = 3600
_WORKERS = 4


def sign(secret: str, body: bytes) -> str:
    """Compute the signature of a delivery.

    Args:
        secret: The secret shared with the provider.
        body: The exact bytes delivered.

    Returns:
        The value of the header.
    """
    return "sha256=" + hmac.new(secret.encode("utf-8"), body, hashlib.sha256).hexdigest()


def _is_recent(timestamp: object, now: float) -> bool:
    try:
        return abs(now - float(str(timestamp))) <= MAX_AGE_SECONDS
    except ValueError:
        return False


def add_webhooks(
    app: FastAPI,
    service: ChannelService,
    secret: str,
    pool: ThreadPoolExecutor | None = None,
    now: Callable[[], float] = time.time,
) -> ThreadPoolExecutor:
    """Add the webhooks to the application.

    Args:
        app: The application.
        service: The channels.
        secret: The secret shared with the provider.
        pool: Where messages are answered, since a provider will not wait for a model.
        now: Provides the current time, in seconds.

    Returns:
        The pool, for whoever has to shut it down.
    """
    workers = pool or ThreadPoolExecutor(max_workers=_WORKERS, thread_name_prefix="channel")

    def submit(task: Callable[..., None], *args: Any) -> None:
        def guarded() -> None:
            try:
                task(*args)
            except Exception:
                # Nobody is waiting for this thread, so a failure would otherwise go unseen.
                LOG.exception("A message from a channel could not be answered")

        workers.submit(guarded)

    async def verified_json(request: Request) -> dict[str, Any]:
        body = await request.body()
        presented = request.headers.get(SIGNATURE_HEADER, "")
        if not hmac.compare_digest(presented.encode("utf-8"), sign(secret, body).encode("utf-8")):
            raise HTTPException(status_code=401, detail={"code": "BAD_SIGNATURE"})
        try:
            parsed = json.loads(body)
        except ValueError as error:
            raise HTTPException(status_code=400, detail={"code": "NOT_JSON"}) from error
        if not isinstance(parsed, dict):
            raise HTTPException(status_code=400, detail={"code": "NOT_AN_OBJECT"})
        return parsed

    @app.post("/webhooks/whatsapp", include_in_schema=False)
    async def whatsapp(request: Request) -> dict[str, str]:
        """Take a delivery from WhatsApp, and answer it in the background.

        Args:
            request: The delivery.

        Returns:
            An acknowledgement, whatever it held: a provider only needs to know that it arrived.
        """
        payload = await verified_json(request)
        for value in _whatsapp_values(payload):
            names = {
                contact.get("wa_id"): contact.get("profile", {}).get("name")
                for contact in value.get("contacts", [])
                if isinstance(contact, dict) and isinstance(contact.get("profile", {}), dict)
            }
            for message in value.get("messages", []):
                _take_whatsapp_message(message, names)
        return {"status": "ok"}

    def _take_whatsapp_message(message: object, names: dict[Any, Any]) -> None:
        if not isinstance(message, dict):
            return
        message_id, sender = message.get("id"), message.get("from")
        if not isinstance(message_id, str) or not isinstance(sender, str):
            return
        if not _is_recent(message.get("timestamp"), now()) or not service.is_new(
            "whatsapp", message_id
        ):
            return
        phone = sender if sender.startswith("+") else f"+{sender}"
        text_part = message.get("text")
        text = text_part.get("body") if isinstance(text_part, dict) else None
        if message.get("type") != "text" or not isinstance(text, str) or not text.strip():
            submit(service.reply_text_only, phone)
            return
        name = names.get(sender)
        submit(service.handle_whatsapp, phone, name if isinstance(name, str) else None, text)

    @app.post("/webhooks/email", include_in_schema=False)
    async def email(request: Request) -> dict[str, str]:
        """Take a delivery of an email, and answer it in the background.

        Args:
            request: The delivery.

        Returns:
            An acknowledgement.
        """
        payload = await verified_json(request)
        message_id, sender = payload.get("message_id"), payload.get("from")
        if not isinstance(message_id, str) or not isinstance(sender, str):
            return {"status": "ignored"}
        if not _is_recent(payload.get("timestamp"), now()) or not service.is_new(
            "email", message_id
        ):
            return {"status": "ignored"}
        # Anyone can write any address in an email, so it means nothing unless the provider checked
        # it. Nor is an answer sent, since it would go to whoever is being impersonated.
        if payload.get("sender_verified") is not True:
            LOG.warning("An email whose sender could not be verified was dropped")
            return {"status": "ignored"}
        subject, body = payload.get("subject"), payload.get("text")
        parts = [part for part in (subject, body) if isinstance(part, str)]
        # Only the plain text is read: what an HTML part hides from people must not reach the agent.
        text = strip_quoted_reply("\n".join(parts))
        if text:
            submit(service.handle_email, sender.strip().lower(), text)
        return {"status": "ok"}

    return workers


def _whatsapp_values(payload: dict[str, Any]) -> list[dict[str, Any]]:
    values = []
    entries = payload.get("entry")
    for entry in entries if isinstance(entries, list) else []:
        changes = entry.get("changes") if isinstance(entry, dict) else None
        for change in changes if isinstance(changes, list) else []:
            value = change.get("value") if isinstance(change, dict) else None
            if isinstance(value, dict):
                values.append(
                    {
                        "contacts": value["contacts"]
                        if isinstance(value.get("contacts"), list)
                        else [],
                        "messages": value["messages"]
                        if isinstance(value.get("messages"), list)
                        else [],
                    }
                )
    return values
