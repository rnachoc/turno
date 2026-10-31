"""Rules decided by code, before and after the model.

The model can be wrong or manipulated, so what must always hold does not depend on it. These checks
favour safety: a false alarm sends a patient to a person, while a miss could cost much more.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import re
import unicodedata
from dataclasses import dataclass
from enum import Enum

MAX_REPLY_CHARS = 1500


class Triage(Enum):
    """What a patient's message requires, before the model sees it."""

    ROUTINE = "routine"
    EMERGENCY = "emergency"
    MEDICAL_QUESTION = "medical_question"


# Matched against lower-case text without accents.
_EMERGENCY = re.compile(
    r"(no|sin) (puedo |puede |poder |consigo |logro )?respir\w*|(me|le) cuesta respirar|"
    r"dificultad(es)? para respirar|me (estoy )?ahog\w*|se (esta )?ahog\w*|"
    r"(me|le) falta (el )?aire|falta de aire|"
    r"(can'?t|cannot|can not|couldn'?t|not|struggling to|difficulty|trouble) breath\w*|"
    r"hard to breathe|short(ness)? of breath|choking|"
    r"dolor .{0,15}pecho|(me|le) duele .{0,15}pecho|"
    r"chest (pain|hurts)|pain in (my|the|his|her) chest|"
    r"infarto|heart attack|convulsi\w*|seizure|"
    r"me muero(?! de (ganas|risa|hambre|sed|sueno|verguenza|calor|frio|pena|aburrimiento|envidia))|"
    r"me estoy muriendo|i'?m dying|i am dying|es una emergencia|(this is|it'?s) an emergency|"
    r"(hincha\w*|inflama\w*) .{0,25}(cara|cuello|garganta|ojo)|"
    r"(cara|cuello|garganta|ojo) .{0,25}(hincha\w*|inflama\w*)|"
    r"(swollen|swelling|swells?) .{0,25}(face|neck|throat|eye)|"
    r"(face|neck|throat) .{0,15}(swollen|swelling)|"
    r"sangr\w+ .{0,25}(no para|no se para|sin parar|mucho|abundante)|"
    r"(no para|no deja) de sangrar|bleeding .{0,25}(won'?t stop|not stop|heavily|a lot)|"
    r"(won'?t|doesn'?t|does not) stop bleeding|"
    r"me (he )?desmay\w*|(he|i) (fainted|passed out)|inconsciente|unconscious|"
    r"accidente|golpe .{0,25}(cabeza|cara|boca)|(hit|struck) .{0,25}(head|face|mouth)|"
    r"anafilax\w*|anaphyla\w*|reaccion alergica|allergic reaction|"
    r"fiebre (muy )?alta|high fever|"
    r"suicid\w*|quitarme la vida|hacerme dano|kill myself|hurt myself|end my life"
)

_MEDICAL_QUESTION = re.compile(
    r"antibiotic\w*|ibuprofen\w*|paracetamol\w*|nolotil\w*|amoxicilin\w*|aspirin\w*|"
    r"analgesic\w*|painkiller\w*|medicament\w*|medicin\w*|medication\w*|pastilla\w*|"
    r"dosis|dosage|dose\b|que (me )?(tomo|puedo tomar|debo tomar)|"
    r"what (can|should) i take|diagnostic\w*|diagnos\w*|"
    r"es grave|is (it|this) serious|es normal que|is it normal"
)

_AFFIRMATIVE = re.compile(
    r"^(si|sip|vale|ok|okay|okey|de acuerdo|perfecto|adelante|confirmo|correcto|claro|"
    r"yes|yep|yeah|sure|confirm|confirmed|correct|go ahead|that'?s fine|fine|"
    r"lo confirmo|si[, ]+(por favor|confirmo|adelante|vale|claro)|"
    r"yes[, ]+(please|confirm|go ahead))[.!\s]*$"
)

_EMAIL = re.compile(r"[\w.+-]+@[\w-]+(\.[\w-]+)+")
_SYSTEM_PATH = re.compile(
    r"(?<![a-zA-Z])[a-zA-Z]:\\[a-zA-Z][^\s]*|(?<![\w/.])/(home|users|etc|var|root|tmp|usr)/[^\s]*",
    re.IGNORECASE,
)
_SECRET = re.compile(r"sk-ant-[\w-]+|bearer\s+[\w.-]{16,}|local-only-[\w-]+", re.IGNORECASE)
_DOSAGE_ADVICE = re.compile(
    r"\b(toma|tome|tomar|take|tomate)\b.{0,40}\b(\d+\s?(mg|g|ml)|ibuprofeno|ibuprofen|paracetamol|"
    r"antibiotico|antibiotic|amoxicilina|amoxicillin|nolotil|aspirina|aspirin)\b"
)
_STATE_CLAIM = re.compile(
    r"\b(confirmada|confirmado|cancelada|cancelado|reservada|reservado|reprogramada|reprogramado|"
    r"anulada|anulado|retenid[oa]|"
    r"confirmed|cancell?ed|booked|rescheduled|on hold)\b"
)


@dataclass(frozen=True)
class OutboundViolation:
    """Why a reply cannot be sent.

    Attributes:
        reason: A stable code for the trace.
        correctable: Whether the model may be asked to try again.
    """

    reason: str
    correctable: bool


def normalize(text: str) -> str:
    """Prepare text for matching: lower case, no accents or invisible characters, single spaces.

    Args:
        text: The text.

    Returns:
        The normalized text.
    """
    decomposed = unicodedata.normalize("NFKD", text)
    # Invisible characters would break a word in two for the rules, though not for a reader.
    without_marks = "".join(
        c for c in decomposed if not unicodedata.combining(c) and unicodedata.category(c) != "Cf"
    )
    return " ".join(without_marks.lower().split())


def triage(message: str) -> Triage:
    """Classify a patient's message with fixed rules. The model is not involved.

    Args:
        message: The patient's message.

    Returns:
        What the message requires. An emergency outranks everything else.
    """
    text = normalize(message)
    if _EMERGENCY.search(text):
        return Triage.EMERGENCY
    if _MEDICAL_QUESTION.search(text):
        return Triage.MEDICAL_QUESTION
    return Triage.ROUTINE


def is_affirmative(message: str) -> bool:
    """Tell whether a message is a clear yes, and nothing else.

    A longer sentence is deliberately not a yes: "yes, but not on Monday" must not confirm anything.

    Args:
        message: The patient's message.

    Returns:
        Whether it is an unambiguous yes.
    """
    return _AFFIRMATIVE.match(normalize(message)) is not None


def check_outbound(reply: str, state_backed: bool) -> OutboundViolation | None:
    """Check a reply before it reaches a patient.

    Args:
        reply: The reply written by the model.
        state_backed: Whether in this turn the agent read or changed an appointment in the core, so
            that a claim about its state rests on something real.

    Returns:
        The violation, or None if the reply can be sent.
    """
    if not reply.strip():
        return OutboundViolation("empty_reply", correctable=True)
    if len(reply) > MAX_REPLY_CHARS:
        return OutboundViolation("reply_too_long", correctable=True)
    # The model's context includes the account's email and the machine's paths, which no documented
    # option removes. Patients never give an email in this flow, so none may appear.
    if _EMAIL.search(reply):
        return OutboundViolation("email_address", correctable=False)
    if _SYSTEM_PATH.search(reply):
        return OutboundViolation("system_path", correctable=False)
    if _SECRET.search(reply):
        return OutboundViolation("secret", correctable=False)
    normalized = normalize(reply)
    if _DOSAGE_ADVICE.search(normalized):
        return OutboundViolation("medical_advice", correctable=False)
    if not state_backed and _STATE_CLAIM.search(normalized):
        return OutboundViolation("unbacked_state_claim", correctable=True)
    return None
