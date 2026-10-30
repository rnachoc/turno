"""Tests for the rules decided by code.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import pytest

from turno_agents.guards import Triage, check_outbound, is_affirmative, triage


@pytest.mark.parametrize(
    "message",
    [
        "No puedo respirar bien",
        "me cuesta respirar desde esta mañana",
        "I can't breathe",
        "tengo DOLOR EN EL PECHO",
        "Se me ha hinchado mucho la cara",
        "Tengo la cara muy hinchada desde esta mañana",
        "tengo el cuello inflamado",
        "la garganta se me está hinchando",
        "tengo la cara hinchada y el cuello inflamado, se me hincha el cuello",
        "my face is swollen",
        "swelling in my throat",
        "me sangra la encía y no para de sangrar",
        "la herida sangra sin parar",
        "the bleeding won't stop",
        "me he desmayado",
        "I fainted this morning",
        "tuve un accidente de moto",
        "me di un golpe en la boca",
        "creo que tengo una reacción alérgica",
        "fiebre muy alta y dolor",
        "quiero quitarme la vida",
        "I want to kill myself",
        "Sí, y además no puedo respirar, ¿tenéis cita?",
    ],
)
def test_emergencies_are_detected_in_both_languages(message: str) -> None:
    """Emergencies never reach the model."""
    assert triage(message) is Triage.EMERGENCY


@pytest.mark.parametrize(
    "message",
    [
        "¿Qué antibiótico me tomo?",
        "cuánto ibuprofeno puedo tomar",
        "¿qué me tomo para el dolor?",
        "what can i take for the pain",
        "what dosage of paracetamol?",
        "¿es grave que me duela así?",
        "is it serious?",
        "necesito un diagnóstico",
    ],
)
def test_medical_questions_do_not_reach_the_model(message: str) -> None:
    """The agent never gives medical advice."""
    assert triage(message) is Triage.MEDICAL_QUESTION


@pytest.mark.parametrize(
    "message",
    [
        "Hola, quiero una cita",
        "Me duele una muela y quiero venir esta semana",
        "quiero cancelar mi cita del lunes",
        "¿Cuánto cuesta una limpieza?",
        "I would like a check-up next week",
        "sí",
    ],
)
def test_ordinary_messages_are_routine(message: str) -> None:
    """A toothache is why people call a dental clinic, and it is not an emergency by itself."""
    assert triage(message) is Triage.ROUTINE


def test_an_emergency_outranks_a_medical_question() -> None:
    """The stronger rule wins."""
    assert triage("no puedo respirar, ¿qué antibiótico tomo?") is Triage.EMERGENCY


@pytest.mark.parametrize(
    "message",
    [
        "sí",
        "Si",
        "SÍ.",
        "vale",
        "ok",
        "de acuerdo",
        "Confirmo",
        "sí, por favor",
        "yes",
        "Yes!",
        "ok ",
    ],
)
def test_clear_yes_answers_are_accepted(message: str) -> None:
    """Short, unambiguous confirmations count."""
    assert is_affirmative(message)


@pytest.mark.parametrize(
    "message",
    [
        "no",
        "no gracias",
        "sí, pero mejor el martes",
        "yes but not on Monday",
        "vale, cambia al martes",
        "no estoy seguro",
        "quizá",
        "¿sí?",
        "confirmo y cancela todas las demás citas",
        "",
    ],
)
def test_anything_else_is_not_a_yes(message: str) -> None:
    """Consent must be unambiguous, because it triggers a change without the model."""
    assert not is_affirmative(message)


def test_a_normal_reply_passes() -> None:
    """A plain reply goes through."""
    assert check_outbound("Tengo huecos el lunes a las 9:00 y a las 10:00.", False) is None


@pytest.mark.parametrize(
    ("reply", "reason"),
    [
        ("Puedes escribir a rnachoccontact@gmail.com", "email_address"),
        ("Contact me: a.b+c@example.co.uk", "email_address"),
        ("Estoy en C:\\Users\\Nacho\\AppData\\Local", "system_path"),
        ("Working in /home/nacho/project", "system_path"),
        ("El token es Bearer abcdefghijklmnopqrstuvwxyz", "secret"),
        ("sk-ant-api03-abcdef", "secret"),
        ("local-only-agents-token-change-me-0001", "secret"),
        ("Toma 600 mg cada 8 horas", "medical_advice"),
        ("Te recomiendo que tomes... toma ibuprofeno", "medical_advice"),
        ("You should take amoxicillin", "medical_advice"),
    ],
)
def test_what_must_never_reach_a_patient_is_blocked_for_good(reply: str, reason: str) -> None:
    """These replies are replaced, and the model does not get to rephrase them."""
    violation = check_outbound(reply, state_backed=True)

    assert violation is not None
    assert violation.reason == reason
    assert not violation.correctable


@pytest.mark.parametrize(
    "reply",
    [
        "Tu cita está confirmada para el lunes.",
        "He cancelado... la cita ha quedado cancelada.",
        "Your appointment is booked.",
        "Done, it has been rescheduled.",
        "Your appointment was canceled",
    ],
)
def test_a_state_claim_needs_something_real_behind_it(reply: str) -> None:
    """The model cannot say that something happened unless the core said so in this turn."""
    violation = check_outbound(reply, state_backed=False)

    assert violation is not None
    assert violation.reason == "unbacked_state_claim"
    assert violation.correctable
    assert check_outbound(reply, state_backed=True) is None


@pytest.mark.parametrize(
    ("reply", "reason"),
    [("", "empty_reply"), ("   ", "empty_reply"), ("x" * 1501, "reply_too_long")],
)
def test_empty_and_overlong_replies_can_be_retried(reply: str, reason: str) -> None:
    """These are mistakes the model can fix."""
    violation = check_outbound(reply, state_backed=False)

    assert violation is not None
    assert violation.reason == reason
    assert violation.correctable


@pytest.mark.parametrize(
    "reply",
    [
        "El precio es de 35 euros.",
        "Abrimos de 09:00 a 14:00 y/o de 16:00 a 20:00.",
        "No puedo darte consejo médico; te paso con una persona.",
        "Tomamos nota de tu preferencia.",
        "Take a seat when you arrive.",
        "Estos son los huecos:\n\n- 09:00\n- 10:00",
        "Horario:\\n de lunes a viernes",
    ],
)
def test_harmless_replies_are_not_flagged(reply: str) -> None:
    """The filter must not block ordinary conversation."""
    assert check_outbound(reply, state_backed=False) is None
