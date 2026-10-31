"""Turns messages from the channels into conversations, and offers into messages.

An incoming message is untrusted in every part except the address, which the provider vouches for.
That address is what binds a conversation to a patient, exactly as a phone number does in the web
chat. Nothing a message says can change who it is about.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import logging
import re
import threading
import uuid
from collections import OrderedDict
from typing import Any

from turno_agents.agent import AgentService
from turno_agents.channels.gateway import Channel, Gateway, GatewayError
from turno_agents.channels.texts import fixed, offer_text
from turno_agents.conversation import (
    Conversation,
    ConversationStore,
    PendingConsent,
    TraceStep,
)
from turno_agents.core_client import CoreApi, CoreRefusalError, CoreUnavailableError
from turno_agents.guards import Triage, normalize, triage
from turno_agents.limits import WindowLimiter
from turno_agents.prompts import agent_block, fixed_text
from turno_agents.proposals import ConfirmAppointment

LOG = logging.getLogger(__name__)

# What the agent reads at most of one message, as in the web chat. Triage reads all of it.
MAX_MESSAGE_CHARACTERS = 1000
# A provider retries a delivery it is not sure about, so each identifier is only heard once.
_MAX_REMEMBERED_MESSAGES = 10_000
_NEGATIVE = re.compile(
    r"^(no|nop|no gracias|no, gracias|no quiero|no puedo|no thanks|no, thanks)[.!\s]*$"
)
_REPLY_BOUNDARY = re.compile(
    r"^\s*(>|-- ?$|-{3,}\s*(original|mensaje)|(el|on) .{0,120}(escribio|wrote):\s*$)", re.IGNORECASE
)


def strip_quoted_reply(text: str) -> str:
    """Keep what someone wrote in an email, without the older messages below it.

    Args:
        text: The plain text of the email.

    Returns:
        The new part.
    """
    kept = []
    for line in text.splitlines():
        if _REPLY_BOUNDARY.match(normalize(line) if line.strip() else line):
            break
        kept.append(line)
    return "\n".join(kept).strip()


class ChannelService:
    """Keeps one conversation per address, and passes messages to the agent."""

    def __init__(
        self,
        agent: AgentService,
        core: CoreApi,
        store: ConversationStore,
        gateway: Gateway,
        limiter: WindowLimiter,
    ) -> None:
        """Create the service.

        Args:
            agent: The agent.
            core: Access to the core.
            store: Where conversations are kept.
            gateway: How messages go out.
            limiter: The limit of messages per address.
        """
        self._agent = agent
        self._core = core
        self._store = store
        self._gateway = gateway
        self._limiter = limiter
        self._lock = threading.Lock()
        self._sessions: dict[str, str] = {}
        self._seen: OrderedDict[str, None] = OrderedDict()

    def is_new(self, channel: Channel, message_id: str) -> bool:
        """Remember a message, and tell whether it is the first time.

        Args:
            channel: Which channel.
            message_id: The provider's identifier.

        Returns:
            Whether it should be handled.
        """
        key = f"{channel}:{message_id}"
        with self._lock:
            if key in self._seen:
                return False
            self._seen[key] = None
            while len(self._seen) > _MAX_REMEMBERED_MESSAGES:
                self._seen.popitem(last=False)
            return True

    def handle_whatsapp(self, phone: str, profile_name: str | None, text: str) -> None:
        """Answer a WhatsApp message.

        Args:
            phone: The sender's number, as the provider vouches for it.
            profile_name: The name on their profile, which they chose.
            text: The message.
        """
        emergency = triage(text) is Triage.EMERGENCY
        try:
            conversation = self._conversation(
                "whatsapp", phone, self._whatsapp_patient(phone, profile_name)
            )
        except _NoPatient:
            self._send(
                "whatsapp", phone, self._emergency_or(emergency, "es", fixed("es", "ask_for_name"))
            )
            return
        except (CoreRefusalError, CoreUnavailableError):
            LOG.warning("A WhatsApp message arrived while the core could not be asked")
            self._send(
                "whatsapp", phone, self._emergency_or(emergency, "es", fixed_text("es", "failure"))
            )
            return
        self._answer("whatsapp", phone, conversation, text, emergency)

    def handle_email(self, address: str, text: str) -> None:
        """Answer an email.

        Args:
            address: The sender's address, as the provider vouches for it.
            text: The new part of the email.
        """
        emergency = triage(text) is Triage.EMERGENCY
        try:
            conversation = self._conversation(
                "email", address, lambda: self._core.find_patient_by_email(address)
            )
        except CoreRefusalError:
            self._send(
                "email", address, self._emergency_or(emergency, "es", fixed("es", "unknown_sender"))
            )
            return
        except CoreUnavailableError:
            LOG.warning("An email arrived while the core could not be asked")
            self._send(
                "email", address, self._emergency_or(emergency, "es", fixed_text("es", "failure"))
            )
            return
        self._answer("email", address, conversation, text, emergency)

    def reply_text_only(self, phone: str) -> None:
        """Say that only text can be read, for a photo, a voice note or anything else.

        Args:
            phone: The sender's number.
        """
        self._send("whatsapp", phone, fixed("es", "text_only"))

    def _emergency_or(self, emergency: bool, language: str, otherwise: str) -> str:
        return self._agent.emergency_text(language) if emergency else otherwise

    def _whatsapp_patient(self, phone: str, profile_name: str | None) -> Any:
        def find() -> dict[str, Any]:
            try:
                return self._core.find_patient(phone)
            except CoreRefusalError as error:
                if error.code != "PATIENT_NOT_FOUND":
                    raise
            if not profile_name:
                raise _NoPatient
            try:
                return self._core.register_patient(profile_name, phone, "es")
            except CoreRefusalError as error:
                # A profile can say anything, and the core is the one that decides what is a name.
                raise _NoPatient from error

        return find

    def _conversation(self, channel: Channel, address: str, find_patient: Any) -> Conversation:
        key = f"{channel}:{address}"
        with self._lock:
            known = self._sessions.get(key)
        conversation = self._store.get(known) if known is not None else None
        if conversation is not None:
            return conversation
        patient = find_patient()
        conversation = self._store.create(patient["id"], patient["language"])
        with self._lock:
            self._sessions[key] = conversation.id
        return conversation

    def _answer(
        self, channel: Channel, address: str, conversation: Conversation, text: str, emergency: bool
    ) -> None:
        if not self._limiter.allow(f"{channel}:{address}") and not emergency:
            LOG.warning("A sender went over the limit of messages, and is not being answered")
            return
        if emergency and not conversation.lock.acquire(blocking=False):
            # A message in progress must not leave someone without the number.
            self._send(channel, address, self._agent.answer_emergency(conversation))
            return
        if not emergency:
            conversation.lock.acquire()
        try:
            reply = (
                self._agent.answer_emergency(conversation)
                if emergency
                else self._reply(conversation, text[:MAX_MESSAGE_CHARACTERS])
            )
        finally:
            conversation.lock.release()
        self._send(channel, address, reply)

    def _reply(self, conversation: Conversation, text: str) -> str:
        pending = conversation.pending
        offered = pending is not None and pending.description == _OFFER
        if pending is not None and offered and _NEGATIVE.match(normalize(text)):
            # A clear no frees the slot for the next person at once, with no model involved.
            conversation.pending = None
            conversation.trace.append(TraceStep("consent", "not_given"))
            appointment = str(pending.action.appointment_id)
            try:
                self._core.release(conversation.patient_id, appointment, f"decline-{appointment}")
            except CoreRefusalError:
                return fixed(conversation.language, "offer_gone")
            except CoreUnavailableError:
                # The offer runs out by itself, so nothing is lost beyond some time.
                LOG.warning("An offer could not be given up because the core could not be reached")
            return fixed(conversation.language, "declined")
        return self._agent.handle(conversation, text)

    def _send(self, channel: Channel, address: str, text: str) -> None:
        try:
            self._gateway.send(channel, address, text)
        except GatewayError:
            LOG.error("A reply could not be handed over")

    def send_pending_notices(self) -> None:
        """Announce the offers the core has made. It never raises: there is always a next round."""
        try:
            notices = self._core.pending_notifications()
            clinic = self._core.clinic() if notices else {}
        except (CoreRefusalError, CoreUnavailableError):
            LOG.warning("The notices could not be read, retrying later")
            return
        for notice in notices:
            try:
                self._announce(notice, clinic)
            except (CoreRefusalError, CoreUnavailableError, GatewayError):
                # Sending it again is better than never sending it, so it stays pending.
                LOG.warning("A notice could not be finished, retrying later")
                return

    def _announce(self, notice: dict[str, Any], clinic: dict[str, Any]) -> None:
        phone = notice["phone"]
        patient = {"id": notice["patientId"], "language": notice["language"]}
        conversation = self._conversation("whatsapp", phone, lambda: patient)
        if conversation.patient_id != notice["patientId"]:
            raise CoreRefusalError("PHONE_OF_ANOTHER_PATIENT", 409)
        text = offer_text(notice, clinic)
        self._gateway.send("whatsapp", phone, text)
        with conversation.lock:
            # A yes to this message confirms this hold and nothing else, by the path of any consent.
            conversation.pending = PendingConsent(
                ConfirmAppointment(
                    type="confirm_appointment", appointment_id=uuid.UUID(notice["appointmentId"])
                ),
                _OFFER,
            )
            conversation.transcript.append(agent_block(text))
            conversation.trace.append(TraceStep("consent", "asked:confirm_appointment"))
        self._core.mark_notification_sent(notice["id"], f"notice-{notice['id']}")


class _NoPatient(Exception):
    """Nobody has this address, and nothing allows registering them."""


# Marks a consent that came from an offer, which a plain no can end with no model involved.
_OFFER = "the slot just offered"


class NoticeSender:
    """Sends the notices of offers in the background, so that no request has to wait for it."""

    def __init__(self, service: ChannelService, interval: float = 5.0) -> None:
        """Create the sender.

        Args:
            service: The channels.
            interval: Seconds between rounds.
        """
        self._service = service
        self._interval = interval
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, name="notice-sender", daemon=True)

    def start(self) -> None:
        """Start sending."""
        self._thread.start()

    def stop(self) -> None:
        """Stop sending."""
        self._stop.set()
        self._thread.join()

    def _run(self) -> None:
        while not self._stop.wait(self._interval):
            try:
                self._service.send_pending_notices()
            except Exception:
                # This thread is the only one sending notices, so it must outlive any surprise.
                LOG.exception("The notices could not be sent")
