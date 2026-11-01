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
        "sigo sin poder respirar, me muero",
        "no puedo res\u200bpirar",
        "no puedo respi\u00adrar",
        "no puedo \uff52\uff45\uff53\uff50\uff49\uff52\uff41\uff52",
        "mi hijo no puede respirar",
        "no respiro bien",
        "me falta el aire",
        "se está ahogando",
        "me duele mucho el pecho",
        "tengo un dolor fuerte en el pecho",
        "creo que es un infarto",
        "está teniendo convulsiones",
        "me muero de dolor",
        "me estoy muriendo",
        "es una emergencia",
        "he is not breathing",
        "I'm struggling to breathe",
        "my chest hurts",
        "pain in my chest",
        "I think it's a heart attack",
        "she is having a seizure",
        "I'm dying",
        "this is an emergency",
    ],
)
def test_emergencies_are_detected_in_both_languages(message: str) -> None:
    """Emergencies never reach the model."""
    assert triage(message) is Triage.EMERGENCY


@pytest.mark.parametrize(
    "message",
    [
        "me muero de ganas de que me arregléis la sonrisa",
        "me muero de vergüenza por llegar tarde",
        "quiero una cita para una revisión",
        "¿puedo respirar tranquilo si cancelo a tiempo?",
        "I would like a check-up on Tuesday",
        "no, no quiero cambiarla",
    ],
)
def test_everyday_phrases_are_not_emergencies(message: str) -> None:
    """A rule that fires on anything would teach the clinic to ignore it."""
    assert triage(message) is Triage.ROUTINE


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
        "Sí, gracias",
        "sí, hazlo",
        "sí, confírmala",
        "vale, gracias",
        "yes, thanks",
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


@pytest.mark.parametrize("message", ["Sí, resérvala", "resérvamela", "yes, book it", "Book it."])
def test_asking_to_book_is_a_yes_only_to_a_booking(message: str) -> None:
    """Said while a cancellation waits, it makes no sense, so that cancellation must not run."""
    assert is_affirmative(message, "confirm_appointment")
    assert not is_affirmative(message, "cancel_appointment")
    assert not is_affirmative(message)


@pytest.mark.parametrize(
    "message", ["sí, resérvala pero el martes", "resérvala y cancela las demás", "reserva otra"]
)
def test_asking_to_book_and_something_else_is_not_a_yes(message: str) -> None:
    """The whole message has to agree."""
    assert not is_affirmative(message, "confirm_appointment")


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


@pytest.mark.parametrize(
    "reply",
    [
        "Sin problema, no he reservado nada.",
        "De acuerdo, no queda reservada ninguna cita.",
        "No está confirmada todavía: dime si te viene bien.",
        "Tu cita aún no está confirmada.",
        "Nada ha quedado reservado.",
        "Nothing has been booked.",
        "Your appointment is not confirmed yet.",
        "I haven't booked anything.",
    ],
)
def test_saying_that_nothing_was_done_is_not_a_claim(reply: str) -> None:
    """Patients who decide not to book were handed over for being told just that."""
    assert check_outbound(reply, state_backed=False) is None


@pytest.mark.parametrize(
    "reply",
    [
        "No te preocupes, tu cita queda confirmada.",
        "No, tu cita está confirmada.",
        "No hay problema: reservada para el martes.",
        "No está cancelada, sigue confirmada.",
        "Not a problem, it is booked.",
        "Don't worry, you are booked.",
    ],
)
def test_a_negation_nearby_does_not_excuse_a_claim(reply: str) -> None:
    """Only a negation of the claim itself counts."""
    violation = check_outbound(reply, state_backed=False)

    assert violation is not None
    assert violation.reason == "unbacked_state_claim"
