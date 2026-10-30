"""The state of a conversation, kept in memory for now.

A conversation belongs to one patient, set by the code when it starts. The identifier is never shown
to the model, so nothing written in a message can move the conversation to someone else.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import threading
import uuid
from dataclasses import dataclass, field
from enum import Enum
from typing import Any

from turno_agents.proposals import ConsentAction

MAX_CONVERSATIONS = 500


class ConversationState(Enum):
    """Where a conversation stands."""

    ACTIVE = "active"
    # A person has to take over, so the model is not called any more.
    WITH_A_PERSON = "with_a_person"


@dataclass
class PendingConsent:
    """A change waiting for the patient's yes.

    Attributes:
        action: Exactly what will run if the patient agrees.
        description: The text shown to the patient, written by the code.
    """

    action: ConsentAction
    description: str


@dataclass
class TraceStep:
    """One thing that happened during a turn, without any patient text.

    Attributes:
        kind: What happened.
        detail: A stable code or name.
        input_tokens: Tokens read by the model, if it was called.
        output_tokens: Tokens written by the model, if it was called.
        estimated_cost_usd: What the call would cost on the API. No money is actually spent.
        seconds: How long the call took.
    """

    kind: str
    detail: str = ""
    input_tokens: int = 0
    output_tokens: int = 0
    estimated_cost_usd: float = 0.0
    seconds: float = 0.0


@dataclass
class Conversation:
    """A conversation with one patient.

    Attributes:
        id: Unguessable identifier.
        patient_id: The patient, known to the code only.
        language: The patient's language.
        state: Where it stands.
        turn: How many messages the patient has sent.
        transcript: What the model gets to read.
        pending: A change waiting for the patient's yes.
        trace: What happened, for debugging and evaluation.
        lock: Serializes the turns of this conversation.
    """

    id: str
    patient_id: str
    language: str
    state: ConversationState = ConversationState.ACTIVE
    turn: int = 0
    transcript: list[str] = field(default_factory=list)
    pending: PendingConsent | None = None
    trace: list[TraceStep] = field(default_factory=list)
    lock: threading.Lock = field(default_factory=threading.Lock)


class ConversationStore:
    """Holds the conversations in progress, dropping the oldest ones when it is full."""

    def __init__(self, capacity: int = MAX_CONVERSATIONS) -> None:
        """Create the store.

        Args:
            capacity: How many conversations to keep.
        """
        self._capacity = capacity
        self._lock = threading.Lock()
        self._conversations: dict[str, Conversation] = {}

    def create(self, patient_id: str, language: str) -> Conversation:
        """Start a conversation.

        Args:
            patient_id: The patient, as established by the channel.
            language: The patient's language.

        Returns:
            The new conversation.
        """
        conversation = Conversation(id=str(uuid.uuid4()), patient_id=patient_id, language=language)
        with self._lock:
            while len(self._conversations) >= self._capacity:
                del self._conversations[next(iter(self._conversations))]
            self._conversations[conversation.id] = conversation
        return conversation

    def get(self, conversation_id: str) -> Conversation | None:
        """Find a conversation.

        Args:
            conversation_id: Its identifier.

        Returns:
            The conversation, if it exists.
        """
        with self._lock:
            return self._conversations.get(conversation_id)


def trace_view(conversation: Conversation) -> list[dict[str, Any]]:
    """Describe what happened in a conversation, for people inspecting it.

    Args:
        conversation: The conversation.

    Returns:
        One entry per step.
    """
    return [vars(step).copy() for step in conversation.trace]
