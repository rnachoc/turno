"""Attacks on the core's API and on the console in front of it, with no model involved.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import json
import secrets
import uuid
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor

import httpx2

from security.bank import Attack, Breach, Stack, Stopper, must_be, refused_with

IDENTITY = "Identity and permissions"
PATIENTS = "Reaching other patients"
FORGERY = "Forged fields"
INPUT = "Hostile input"
RULES = "Breaking the rules"
CONSOLE = "The console"

_ATTACKS: list[Attack] = []


def attack(category: str, goal: str) -> Callable[[Callable[[Stack], Stopper]], None]:
    """Add a function to the bank.

    Args:
        category: What kind of attack it is.
        goal: What the attacker is after.

    Returns:
        The decorator.
    """

    def add(run: Callable[[Stack], Stopper]) -> None:
        _ATTACKS.append(Attack(f"core-{len(_ATTACKS) + 1:02d}", category, goal, run))

    return add


def core_attacks() -> list[Attack]:
    """List the attacks of this module, the ones that use up a limit at the end.

    Returns:
        The attacks, in the order to run them.
    """
    return list(_ATTACKS)


@attack(IDENTITY, "Read the audit trail with no token")
def _no_token(stack: Stack) -> Stopper:
    refused_with(stack.anonymous.get("/api/v1/staff/audit"), 401, "UNAUTHENTICATED")
    return Stopper.AUTHENTICATION


@attack(IDENTITY, "Pass the token in the address instead of the header")
def _token_in_query(stack: Stack) -> Stopper:
    token = stack.tokens["staff"]
    response = stack.anonymous.get(
        "/api/v1/staff/audit", params={"access_token": token, "token": token}
    )
    refused_with(response, 401, "UNAUTHENTICATED")
    return Stopper.AUTHENTICATION


@attack(IDENTITY, "Read patients with an almost valid token")
def _almost_a_token(stack: Stack) -> Stopper:
    token = stack.tokens["staff"]
    for forged in (token + "x", token[:-1], token.upper(), f"{token},{token}", f"Basic {token}"):
        response = stack.anonymous.get(
            "/api/v1/staff/audit", headers={"Authorization": f"Bearer {forged}"}
        )
        refused_with(response, 401, "UNAUTHENTICATED")
    return Stopper.AUTHENTICATION


@attack(IDENTITY, "Read the audit trail with the agents' token")
def _agent_reads_audit(stack: Stack) -> Stopper:
    refused_with(stack.agents.get("/api/v1/staff/audit"), 403, "FORBIDDEN")
    return Stopper.PERMISSION


@attack(IDENTITY, "Charge a no-show with the agents' token")
def _agent_no_show(stack: Stack) -> Stopper:
    patient = stack.new_patient()
    appointment = stack.book(patient)
    refused_with(
        stack.post(stack.agents, f"/api/v1/staff/appointments/{appointment}/no-show"),
        403,
        "FORBIDDEN",
    )
    if stack.status_of(patient, appointment) != "CONFIRMED":
        raise Breach("the appointment changed")
    return Stopper.PERMISSION


@attack(IDENTITY, "Read the agenda and its phone numbers with the agents' token")
def _agent_reads_agenda(stack: Stack) -> Stopper:
    refused_with(stack.agents.get("/api/v1/staff/agenda"), 403, "FORBIDDEN")
    return Stopper.PERMISSION


@attack(IDENTITY, "Force an offer through the worker's API with the agents' token")
def _agent_forces_offer(stack: Stack) -> Stopper:
    path = f"/api/v1/workflows/freed-slots/{uuid.uuid4()}/next-offer"
    refused_with(stack.post(stack.agents, path), 403, "FORBIDDEN")
    return Stopper.PERMISSION


@attack(IDENTITY, "Read a patient with the worker's token")
def _worker_reads_patient(stack: Stack) -> Stopper:
    patient = stack.new_patient()
    refused_with(stack.worker.get(f"/api/v1/patients/{patient}"), 403, "FORBIDDEN")
    return Stopper.PERMISSION


@attack(IDENTITY, "Turn a read into a write with a method override")
def _method_override(stack: Stack) -> Stopper:
    patient = stack.new_patient()
    appointment = stack.book(patient)
    path = f"/api/v1/patients/{patient}/appointments/{appointment}"
    response = stack.agents.get(
        path, headers={"X-HTTP-Method-Override": "DELETE", "X-Method-Override": "POST"}
    )
    must_be(response, 200)
    if stack.status_of(patient, appointment) != "CONFIRMED":
        raise Breach("the appointment changed")
    return Stopper.NO_CAPABILITY


@attack(IDENTITY, "Reach a staff route by rewriting its address")
def _rewritten_paths(stack: Stack) -> Stopper:
    with httpx2.Client(
        base_url=stack.core_url, headers={"Authorization": f"Bearer {stack.tokens['agents']}"}
    ) as client:
        for path in (
            "/api/v1/staff/audit/",
            "/api/v1/staff/audit;x=1",
            "/api/v1/staff;x=1/audit",
            "//api/v1/staff/audit",
            "/api/v1/./staff/audit",
            "/api/v1/patients/../staff/audit",
            "/api/v1/patients/%2e%2e/staff/audit",
            "/api/v1/staff/%61udit",
            "/API/V1/STAFF/AUDIT",
        ):
            request = client.build_request(
                "GET", httpx2.URL(stack.core_url).copy_with(raw_path=path.encode())
            )
            response = client.send(request)
            if response.status_code == 200:
                raise Breach(f"{path} answered 200")
    return Stopper.PERMISSION


@attack(PATIENTS, "Read another patient's appointment")
def _read_other(stack: Stack) -> Stopper:
    owner, other = stack.new_patient(), stack.new_patient()
    appointment = stack.book(owner)
    refused_with(
        stack.agents.get(f"/api/v1/patients/{other}/appointments/{appointment}"),
        404,
        "APPOINTMENT_NOT_FOUND",
    )
    return Stopper.OWNERSHIP


@attack(PATIENTS, "Cancel another patient's appointment")
def _cancel_other(stack: Stack) -> Stopper:
    owner, other = stack.new_patient(), stack.new_patient()
    appointment = stack.book(owner)
    refused_with(
        stack.post(stack.agents, f"/api/v1/patients/{other}/appointments/{appointment}/cancel"),
        404,
        "APPOINTMENT_NOT_FOUND",
    )
    if stack.status_of(owner, appointment) != "CONFIRMED":
        raise Breach("the appointment changed")
    return Stopper.OWNERSHIP


@attack(PATIENTS, "Move another patient's appointment")
def _move_other(stack: Stack) -> Stopper:
    owner, other = stack.new_patient(), stack.new_patient()
    appointment = stack.book(owner)
    slot = stack.free_slot()
    refused_with(
        stack.post(
            stack.agents,
            f"/api/v1/patients/{other}/appointments/{appointment}/reschedule",
            {"practitionerId": slot["practitionerId"], "startsAt": slot["startsAt"]},
        ),
        404,
        "APPOINTMENT_NOT_FOUND",
    )
    return Stopper.OWNERSHIP


@attack(PATIENTS, "Confirm a hold that belongs to someone else")
def _confirm_other(stack: Stack) -> Stopper:
    owner, other = stack.new_patient(), stack.new_patient()
    appointment = stack.hold(owner, stack.free_slot())
    refused_with(
        stack.post(stack.agents, f"/api/v1/patients/{other}/appointments/{appointment}/confirm"),
        404,
        "APPOINTMENT_NOT_FOUND",
    )
    if stack.status_of(owner, appointment) != "HOLD":
        raise Breach("the hold changed")
    return Stopper.OWNERSHIP


@attack(PATIENTS, "Take someone off the waitlist through another patient")
def _leave_other(stack: Stack) -> Stopper:
    owner, other = stack.new_patient(), stack.new_patient()
    joined = stack.post(
        stack.agents, f"/api/v1/patients/{owner}/waitlist", {"treatmentId": "check-up"}
    )
    must_be(joined, 201)
    entry = joined.json()["id"]
    refused_with(
        stack.post(stack.agents, f"/api/v1/patients/{other}/waitlist/{entry}/leave"),
        404,
        "WAITLIST_ENTRY_NOT_FOUND",
    )
    left = stack.post(stack.agents, f"/api/v1/patients/{owner}/waitlist/{entry}/leave")
    must_be(left, 200)
    return Stopper.OWNERSHIP


@attack(PATIENTS, "Add steps to the conversation of another patient")
def _steps_other(stack: Stack) -> Stopper:
    owner, other = stack.new_patient(), stack.new_patient()
    conversation = uuid.uuid4()
    step = {
        "kind": "action",
        "detail": "x",
        "inputTokens": 0,
        "outputTokens": 0,
        "estimatedCostUsd": 0,
        "seconds": 0,
    }
    body = {"firstPosition": 0, "steps": [step]}
    must_be(
        stack.post(
            stack.agents, f"/api/v1/patients/{owner}/conversations/{conversation}/steps", body
        ),
        200,
    )
    refused_with(
        stack.post(
            stack.agents,
            f"/api/v1/patients/{other}/conversations/{conversation}/steps",
            {**body, "firstPosition": 1},
        ),
        404,
        "CONVERSATION_NOT_FOUND",
    )
    return Stopper.OWNERSHIP


@attack(FORGERY, "Set your own price when holding a slot")
def _forged_price(stack: Stack) -> Stopper:
    patient = stack.new_patient()
    slot = stack.free_slot()
    body = {
        "practitionerId": slot["practitionerId"],
        "treatmentId": slot["treatmentId"],
        "startsAt": slot["startsAt"],
        "priceCents": 1,
        "depositCents": 0,
    }
    refused_with(
        stack.post(stack.agents, f"/api/v1/patients/{patient}/appointments", body),
        400,
        "VALIDATION_FAILED",
    )
    return Stopper.VALIDATION


@attack(FORGERY, "Book straight away by sending the status")
def _forged_status(stack: Stack) -> Stopper:
    patient = stack.new_patient()
    slot = stack.free_slot()
    body = {
        "practitionerId": slot["practitionerId"],
        "treatmentId": slot["treatmentId"],
        "startsAt": slot["startsAt"],
        "status": "CONFIRMED",
    }
    refused_with(
        stack.post(stack.agents, f"/api/v1/patients/{patient}/appointments", body),
        400,
        "VALIDATION_FAILED",
    )
    return Stopper.VALIDATION


@attack(FORGERY, "Hold a slot in another patient's name through the body")
def _forged_patient(stack: Stack) -> Stopper:
    patient, other = stack.new_patient(), stack.new_patient()
    slot = stack.free_slot()
    body = {
        "practitionerId": slot["practitionerId"],
        "treatmentId": slot["treatmentId"],
        "startsAt": slot["startsAt"],
        "patientId": other,
    }
    refused_with(
        stack.post(stack.agents, f"/api/v1/patients/{patient}/appointments", body),
        400,
        "VALIDATION_FAILED",
    )
    return Stopper.VALIDATION


@attack(FORGERY, "Choose your own identifier when registering")
def _forged_id(stack: Stack) -> Stopper:
    body = {
        "fullName": "Forged",
        "phone": f"+3466{secrets.randbelow(10**7):07d}",
        "language": "es",
        "id": str(uuid.uuid4()),
    }
    refused_with(stack.post(stack.agents, "/api/v1/patients", body), 400, "VALIDATION_FAILED")
    return Stopper.VALIDATION


@attack(FORGERY, "Backdate what the agents report")
def _forged_date(stack: Stack) -> Stopper:
    patient = stack.new_patient()
    step = {
        "kind": "action",
        "detail": "x",
        "inputTokens": 0,
        "outputTokens": 0,
        "estimatedCostUsd": 0,
        "seconds": 0,
        "createdAt": "2020-01-01T00:00:00Z",
    }
    refused_with(
        stack.post(
            stack.agents,
            f"/api/v1/patients/{patient}/conversations/{uuid.uuid4()}/steps",
            {"firstPosition": 0, "steps": [step]},
        ),
        400,
        "VALIDATION_FAILED",
    )
    return Stopper.VALIDATION


@attack(FORGERY, "Store what a patient wrote in the record of the agents")
def _words_in_steps(stack: Stack) -> Stopper:
    patient = stack.new_patient()
    step = {
        "kind": "action",
        "detail": "me duele mucho la muela",
        "inputTokens": 0,
        "outputTokens": 0,
        "estimatedCostUsd": 0,
        "seconds": 0,
    }
    refused_with(
        stack.post(
            stack.agents,
            f"/api/v1/patients/{patient}/conversations/{uuid.uuid4()}/steps",
            {"firstPosition": 0, "steps": [step]},
        ),
        400,
        "VALIDATION_FAILED",
    )
    return Stopper.VALIDATION


@attack(INPUT, "Drop a table through a name")
def _sql_name(stack: Stack) -> Stopper:
    name = "x'); DROP TABLE appointment; --"
    patient = stack.new_patient(name)
    read = stack.agents.get(f"/api/v1/patients/{patient}")
    must_be(read, 200)
    if read.json()["fullName"] != name:
        raise Breach("the name was changed on the way")
    stack.book(patient)
    return Stopper.PARAMETERS


@attack(INPUT, "Read everything through a search value")
def _sql_search(stack: Stack) -> Stopper:
    for value in ("x' OR '1'='1", "x'; SELECT pg_sleep(30); --"):
        response = stack.agents.get(
            "/api/v1/availability",
            params={"treatmentId": value, "from": "2030-01-01", "to": "2030-01-02"},
        )
        refused_with(response, 422, "TREATMENT_NOT_FOUND")
    lookup = stack.post(stack.agents, "/api/v1/patients/lookup", {"phone": "+34' OR '1'='1"})
    must_be(lookup, 400, 404)
    return Stopper.PARAMETERS


@attack(INPUT, "Break the database with a NUL character")
def _nul_character(stack: Stack) -> Stopper:
    body = {
        "fullName": "Nul\u0000Name",
        "phone": f"+3466{secrets.randbelow(10**7):07d}",
        "language": "es",
    }
    refused_with(stack.post(stack.agents, "/api/v1/patients", body), 400, "VALIDATION_FAILED")
    return Stopper.VALIDATION


@attack(INPUT, "Break the database with a NUL character in an email")
def _nul_in_email(stack: Stack) -> Stopper:
    body = {
        "fullName": "Nul Email",
        "phone": f"+3466{secrets.randbelow(10**7):07d}",
        "email": "a\u0000@x.example",
        "language": "es",
    }
    refused_with(stack.post(stack.agents, "/api/v1/patients", body), 400, "VALIDATION_FAILED")
    return Stopper.VALIDATION


@attack(INPUT, "Break the database with a NUL character in the audit filter")
def _nul_in_audit(stack: Stack) -> Stopper:
    response = stack.staff.get("/api/v1/staff/audit", params={"entityId": "a\u0000b"})
    refused_with(response, 400, "VALIDATION_FAILED")
    return Stopper.VALIDATION


@attack(INPUT, "Spoof a name with invisible characters that reverse the text")
def _invisible_name(stack: Stack) -> Stopper:
    body = {
        "fullName": "Laura\u202e tseT",
        "phone": f"+3466{secrets.randbelow(10**7):07d}",
        "language": "es",
    }
    refused_with(stack.post(stack.agents, "/api/v1/patients", body), 400, "VALIDATION_FAILED")
    return Stopper.VALIDATION


@attack(INPUT, "Learn what the server runs from its error pages")
def _server_version(stack: Stack) -> Stopper:
    responses = [
        stack.agents.get("/api/v1/patients/a%2Fb"),
        stack.agents.get("/api/v1/nothing"),
        stack.anonymous.get("/api/v1/clinic"),
    ]
    for response in responses:
        text = (response.text + response.headers.get("Server", "")).lower()
        for tell in ("tomcat", "spring", "jetty", "exception", "stacktrace", "java."):
            if tell in text:
                raise Breach(f"an error mentions {tell}")
    return Stopper.FIXED_ERRORS


@attack(INPUT, "Break the database with a NUL character in a search")
def _nul_in_search(stack: Stack) -> Stopper:
    response = stack.agents.get(
        "/api/v1/availability",
        params={"treatmentId": "check\u0000up", "from": "2030-01-01", "to": "2030-01-02"},
    )
    must_be(response, 400, 422)
    return Stopper.VALIDATION


@attack(INPUT, "Break the encoding with half a character")
def _lone_surrogate(stack: Stack) -> Stopper:
    raw = json.dumps(
        {
            "fullName": "Half \ud800 char",
            "phone": f"+3466{secrets.randbelow(10**7):07d}",
            "language": "es",
        }
    ).encode()
    response = stack.agents.post(
        "/api/v1/patients",
        content=raw,
        headers={"Content-Type": "application/json", "Idempotency-Key": secrets.token_hex(16)},
    )
    must_be(response, 400)
    return Stopper.VALIDATION


@attack(INPUT, "Exhaust memory with a body of a megabyte")
def _huge_body(stack: Stack) -> Stopper:
    body = {"fullName": "x" * 1_000_000, "phone": "+34666000000", "language": "es"}
    must_be(stack.post(stack.agents, "/api/v1/patients", body), 413)
    return Stopper.VALIDATION


@attack(INPUT, "Hide the size of the body")
def _hidden_size(stack: Stack) -> Stopper:
    def chunks() -> object:
        yield b'{"fullName":"x",'
        yield b'"phone":"+34666000001","language":"es"}'

    response = stack.agents.post(
        "/api/v1/patients",
        content=chunks(),  # type: ignore[arg-type]
        headers={"Content-Type": "application/json", "Idempotency-Key": secrets.token_hex(16)},
    )
    must_be(response, 411)
    return Stopper.VALIDATION


@attack(INPUT, "Exhaust the stack with deeply nested JSON")
def _deep_json(stack: Stack) -> Stopper:
    raw = b'{"fullName":' + b"[" * 20_000 + b"]" * 20_000 + b"}"
    response = stack.agents.post(
        "/api/v1/patients",
        content=raw,
        headers={"Content-Type": "application/json", "Idempotency-Key": secrets.token_hex(16)},
    )
    must_be(response, 400)
    return Stopper.VALIDATION


@attack(INPUT, "Hang the service with an absurd number")
def _absurd_number(stack: Stack) -> Stopper:
    patient = stack.new_patient()
    raw = (
        b'{"firstPosition":0,"steps":[{"kind":"model","detail":"x","inputTokens":0,"outputTokens":0,'
        b'"estimatedCostUsd":1e1000000000,"seconds":0}]}'
    )
    response = stack.agents.post(
        f"/api/v1/patients/{patient}/conversations/{uuid.uuid4()}/steps",
        content=raw,
        headers={"Content-Type": "application/json", "Idempotency-Key": secrets.token_hex(16)},
    )
    must_be(response, 400)
    return Stopper.VALIDATION


@attack(INPUT, "Send another format than JSON")
def _other_format(stack: Stack) -> Stopper:
    response = stack.agents.post(
        "/api/v1/patients",
        content=b"<patient><fullName>x</fullName></patient>",
        headers={"Content-Type": "application/xml", "Idempotency-Key": secrets.token_hex(16)},
    )
    must_be(response, 415)
    return Stopper.VALIDATION


@attack(INPUT, "Get an address that is not an identifier to reach the database")
def _not_an_identifier(stack: Stack) -> Stopper:
    for value in ("not-a-uuid", "1%20OR%201=1", "..%2f..%2fetc%2fpasswd"):
        response = stack.agents.get(f"/api/v1/patients/{value}")
        must_be(response, 400, 404)
    return Stopper.VALIDATION


@attack(INPUT, "Have an error repeat what was sent, as a page would run it")
def _reflected(stack: Stack) -> Stopper:
    marker = "<script>alert(1)</script>"
    responses = [
        stack.agents.get(
            "/api/v1/availability", params={"treatmentId": marker, "from": marker, "to": marker}
        ),
        stack.agents.get(f"/api/v1/patients/{marker}"),
        stack.post(
            stack.agents,
            "/api/v1/patients",
            {"fullName": marker, "phone": marker, "language": marker},
        ),
    ]
    for response in responses:
        if marker in response.text or "alert(1)" in response.text:
            raise Breach("an error repeated the input")
    return Stopper.FIXED_ERRORS


@attack(INPUT, "Ask for absurd dates")
def _absurd_dates(stack: Stack) -> Stopper:
    for value in ("%2B999999999-12-31", "-999999999-01-01", "0000-00-00"):
        response = stack.agents.get(
            f"/api/v1/availability?treatmentId=check-up&from={value}&to={value}"
        )
        must_be(response, 400)
        must_be(stack.staff.get(f"/api/v1/staff/reports/daily?date={value}"), 400)
    return Stopper.VALIDATION


@attack(INPUT, "Read the whole audit trail in one request")
def _huge_limit(stack: Stack) -> Stopper:
    response = stack.staff.get("/api/v1/staff/audit", params={"limit": 2_000_000_000})
    must_be(response, 200)
    if len(response.json()) > 200:
        raise Breach("the limit was not applied")
    must_be(stack.staff.get("/api/v1/staff/audit", params={"limit": -1}), 200)
    return Stopper.VALIDATION


@attack(RULES, "Book one slot twice with simultaneous requests")
def _double_booking(stack: Stack) -> Stopper:
    slot = stack.free_slot()
    patients = [stack.new_patient() for _ in range(8)]
    body = {
        "practitionerId": slot["practitionerId"],
        "treatmentId": slot["treatmentId"],
        "startsAt": slot["startsAt"],
    }

    def try_it(patient: str) -> int:
        return stack.post(
            stack.agents, f"/api/v1/patients/{patient}/appointments", body
        ).status_code

    with ThreadPoolExecutor(max_workers=8) as pool:
        statuses = list(pool.map(try_it, patients))
    if statuses.count(201) != 1 or set(statuses) - {201, 409}:
        raise Breach(f"answers were {sorted(statuses)}")
    return Stopper.DATABASE


@attack(RULES, "Act twice by retrying with the same key")
def _retry(stack: Stack) -> Stopper:
    patient = stack.new_patient()
    appointment = stack.hold(patient, stack.free_slot())
    key = secrets.token_hex(16)
    path = f"/api/v1/patients/{patient}/appointments/{appointment}/confirm"
    first = stack.post(stack.agents, path, key=key)
    second = stack.post(stack.agents, path, key=key)
    must_be(first, 200)
    must_be(second, 200)
    if first.text != second.text:
        raise Breach("the retry got a different answer")
    return Stopper.DATABASE


@attack(RULES, "Reuse a key for something else")
def _key_reuse(stack: Stack) -> Stopper:
    key = secrets.token_hex(16)
    phone = f"+3466{secrets.randbelow(10**7):07d}"
    body = {"fullName": "One", "phone": phone, "language": "es"}
    must_be(stack.post(stack.agents, "/api/v1/patients", body, key=key), 201)
    refused_with(
        stack.post(stack.agents, "/api/v1/patients", {**body, "fullName": "Two"}, key=key),
        422,
        "IDEMPOTENCY_KEY_REUSED",
    )
    return Stopper.RULE


@attack(RULES, "Book in the past, on a closed day or far ahead")
def _bad_times(stack: Stack) -> Stopper:
    patient = stack.new_patient()
    slot = stack.free_slot()
    for starts_at in ("2020-01-06T09:00:00Z", "2099-01-05T09:00:00Z"):
        response = stack.post(
            stack.agents,
            f"/api/v1/patients/{patient}/appointments",
            {
                "practitionerId": slot["practitionerId"],
                "treatmentId": slot["treatmentId"],
                "startsAt": starts_at,
            },
        )
        refused_with(response, 422, "SLOT_NOT_BOOKABLE")
    return Stopper.RULE


@attack(RULES, "Hold every slot of the clinic for one patient")
def _hold_everything(stack: Stack) -> Stopper:
    patient = stack.new_patient()
    stack.hold(patient, stack.free_slot())
    slot = stack.free_slot()
    response = stack.post(
        stack.agents,
        f"/api/v1/patients/{patient}/appointments",
        {
            "practitionerId": slot["practitionerId"],
            "treatmentId": slot["treatmentId"],
            "startsAt": slot["startsAt"],
        },
    )
    must_be(response, 409)
    return Stopper.RULE


@attack(RULES, "Have a treatment done by someone who does not perform it")
def _wrong_practitioner(stack: Stack) -> Stopper:
    patient = stack.new_patient()
    slot = stack.free_slot()
    clinic = stack.agents.get("/api/v1/clinic").json()
    for practitioner in clinic["practitioners"]:
        for treatment in clinic["treatments"]:
            if treatment["id"] not in practitioner["treatments"]:
                response = stack.post(
                    stack.agents,
                    f"/api/v1/patients/{patient}/appointments",
                    {
                        "practitionerId": practitioner["id"],
                        "treatmentId": treatment["id"],
                        "startsAt": slot["startsAt"],
                    },
                )
                refused_with(response, 422, "PRACTITIONER_CANNOT_PERFORM")
                return Stopper.RULE
    raise Breach("the demo clinic has no such combination to try")


@attack(RULES, "Cancel what has already been cancelled, for a second freed slot")
def _cancel_twice(stack: Stack) -> Stopper:
    patient = stack.new_patient()
    appointment = stack.book(patient)
    path = f"/api/v1/patients/{patient}/appointments/{appointment}/cancel"
    must_be(stack.post(stack.agents, path), 200)
    refused_with(stack.post(stack.agents, path), 409, "INVALID_STATE")
    return Stopper.RULE


def _console(
    stack: Stack, path: str, token: str | None = None, method: str = "GET"
) -> httpx2.Response:
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    with httpx2.Client(base_url=stack.console_url, headers=headers, timeout=20) as client:
        url = httpx2.URL(stack.console_url).copy_with(raw_path=path.encode())
        return client.send(client.build_request(method, url))


@attack(CONSOLE, "Reach the rest of the API through the console")
def _console_rest_of_api(stack: Stack) -> Stopper:
    for path in ("/api/v1/patients", "/api/v1/workflows/freed-slots", "/api/v1/availability"):
        must_be(_console(stack, path, stack.tokens["agents"]), 404)
    return Stopper.PROXY


@attack(CONSOLE, "Climb out of the staff's routes through the console")
def _console_traversal(stack: Stack) -> Stopper:
    for path in (
        "/api/v1/staff/../patients",
        "/api/v1/staff/%2e%2e/patients",
        "/api/v1/staff/..%2fpatients",
        "/api/v1/staff/../../../actuator/env",
    ):
        response = _console(stack, path, stack.tokens["agents"])
        if response.status_code == 200 and "<app-root>" not in response.text:
            raise Breach(f"{path} reached something")
        if response.status_code >= 500:
            raise Breach(f"{path} answered {response.status_code}")
    return Stopper.PROXY


@attack(CONSOLE, "Reach the core's actuator through the console")
def _console_actuator(stack: Stack) -> Stopper:
    response = _console(stack, "/actuator/health")
    if '"status"' in response.text:
        raise Breach("the actuator answered")
    return Stopper.PROXY


@attack(CONSOLE, "Use the staff's routes with the agents' token through the console")
def _console_agents_token(stack: Stack) -> Stopper:
    refused_with(_console(stack, "/api/v1/staff/agenda", stack.tokens["agents"]), 403, "FORBIDDEN")
    return Stopper.PERMISSION


@attack(CONSOLE, "Write to the description of the clinic through the console")
def _console_write_clinic(stack: Stack) -> Stopper:
    must_be(_console(stack, "/api/v1/clinic", stack.tokens["staff"], "POST"), 403)
    return Stopper.PROXY


@attack(CONSOLE, "Run a script from elsewhere in the console's page")
def _console_policy(stack: Stack) -> Stopper:
    policy = _console(stack, "/").headers.get("Content-Security-Policy", "")
    for needed in (
        "default-src 'none'",
        "script-src 'self'",
        "style-src 'self'",
        "frame-ancestors 'none'",
    ):
        if needed not in policy:
            raise Breach(f"the policy lacks {needed}")
    if "unsafe" in policy:
        raise Breach("the policy allows inline code")
    return Stopper.PROXY


def flood_of_guesses(stack: Stack) -> Stopper:
    """Guess tokens until the core stops listening.

    Args:
        stack: The stack.

    Returns:
        What stopped it.

    Raises:
        Breach: If guessing never gets refused.
    """
    for _ in range(40):
        response = stack.anonymous.get(
            "/api/v1/staff/audit", headers={"Authorization": f"Bearer guess-{secrets.token_hex(8)}"}
        )
        if response.status_code == 429:
            if "Retry-After" not in response.headers:
                raise Breach("it did not say how long to wait")
            return Stopper.LIMIT
        must_be(response, 401)
    raise Breach("40 wrong tokens in a row were all heard")


def flood_of_requests(stack: Stack) -> Stopper:
    """Ask the core more than a clinic ever would.

    Args:
        stack: The stack.

    Returns:
        What stopped it.

    Raises:
        Breach: If it answers everything.
    """

    def ask(_: int) -> int:
        return stack.staff.get("/api/v1/clinic").status_code

    with ThreadPoolExecutor(max_workers=16) as pool:
        statuses = list(pool.map(ask, range(400)))
    if 429 not in statuses:
        raise Breach("400 requests in a row were all answered")
    return Stopper.LIMIT
