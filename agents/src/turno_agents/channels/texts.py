"""What the channels say by themselves, written by the code. No model takes part in any of it.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

_WEEKDAYS = {
    "es": ["lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"],
    "en": ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"],
}
_MONTHS = {
    "es": [
        "enero",
        "febrero",
        "marzo",
        "abril",
        "mayo",
        "junio",
        "julio",
        "agosto",
        "septiembre",
        "octubre",
        "noviembre",
        "diciembre",
    ],
    "en": [
        "January",
        "February",
        "March",
        "April",
        "May",
        "June",
        "July",
        "August",
        "September",
        "October",
        "November",
        "December",
    ],
}
_TEXTS = {
    "es": {
        "offer": (
            "Se ha liberado un hueco en {clinic}: {treatment} con {practitioner}, el {weekday} {day} "
            "de {month} a las {time}. Cuesta {price}{deposit}. Responde SÍ antes de las {deadline} "
            "para quedártelo, o NO para dejárselo a otra persona."
        ),
        "deposit": ", con una señal de {deposit}",
        "declined": "De acuerdo, se lo ofreceremos a otra persona. Sigues en la lista de espera para futuros huecos.",
        "offer_gone": "Ese hueco ya no está disponible. Sigues en la lista de espera.",
        "unknown_sender": "No encontramos esta dirección entre nuestros pacientes. Escríbenos desde el teléfono con el que te registraste.",
        "ask_for_name": "Hola. Para atenderte necesito saber tu nombre completo: escríbelo en tu perfil de WhatsApp y vuelve a escribirnos.",
        "text_only": "Por ahora solo puedo leer mensajes de texto.",
    },
    "en": {
        "offer": (
            "A slot has come up at {clinic}: {treatment} with {practitioner}, on {weekday} {day} "
            "{month} at {time}. It costs {price}{deposit}. Reply YES before {deadline} to take it, "
            "or NO to leave it for someone else."
        ),
        "deposit": ", with a deposit of {deposit}",
        "declined": "All right, we will offer it to someone else. You stay on the waitlist for future slots.",
        "offer_gone": "That slot is no longer available. You stay on the waitlist.",
        "unknown_sender": "We cannot find this address among our patients. Please write from the phone you registered with.",
        "ask_for_name": "Hello. To help you I need your full name: please add it to your WhatsApp profile and write again.",
        "text_only": "For now I can only read text messages.",
    },
}


def fixed(language: str, key: str) -> str:
    """Return a text of the channels.

    Args:
        language: The patient's language.
        key: Which text.

    Returns:
        The text, in English if the language has none.
    """
    return _TEXTS.get(language, _TEXTS["en"])[key]


def _money(cents: int, currency: str) -> str:
    return f"{cents / 100:.2f} {currency}"


def offer_text(notice: dict[str, Any], clinic: dict[str, Any]) -> str:
    """Write the message that announces an offer, from the facts the core gave.

    Args:
        notice: The notification.
        clinic: The clinic.

    Returns:
        The message.
    """
    language = notice["language"] if notice["language"] in _TEXTS else "en"
    zone = ZoneInfo(clinic["timezone"])
    starts = datetime.fromisoformat(notice["startsAt"]).astimezone(zone)
    deadline = datetime.fromisoformat(notice["expiresAt"]).astimezone(zone)
    treatment = next((t for t in clinic["treatments"] if t["id"] == notice["treatmentId"]), None)
    practitioner = next(
        (p for p in clinic["practitioners"] if p["id"] == notice["practitionerId"]), None
    )
    deposit = ""
    if notice["depositCents"]:
        deposit = _TEXTS[language]["deposit"].format(
            deposit=_money(notice["depositCents"], clinic["currency"])
        )
    return _TEXTS[language]["offer"].format(
        clinic=clinic["name"],
        treatment=treatment["name"].get(language, treatment["id"])
        if treatment
        else notice["treatmentId"],
        practitioner=practitioner["name"] if practitioner else notice["practitionerId"],
        weekday=_WEEKDAYS[language][starts.weekday()],
        day=starts.day,
        month=_MONTHS[language][starts.month - 1],
        time=starts.strftime("%H:%M"),
        price=_money(notice["priceCents"], clinic["currency"]),
        deposit=deposit,
        deadline=deadline.strftime("%H:%M"),
    )
