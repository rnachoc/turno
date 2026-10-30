# 7. API authentication and the limits of an agent

- **Status:** Accepted
- **Date:** 2026-10-30
- **Author:** Ignacio Rodriguez Caro

## Context

[ADR 2](0002-the-model-proposes-the-code-decides.md) only works if the core can tell who is calling and limits what each caller can do. Agents read untrusted text, so the core must assume that any agent may have been manipulated, and must keep the damage small when that happens.

## Decision

- **Two tokens, two roles.** `agents` and `staff` each have their own bearer token. The role comes from the token, never from anything the caller claims. The service does not start if a token is missing, shorter than 32 characters, or shared by both roles.
- **Checks on the resolved handler.** Authentication runs in a Spring MVC interceptor, after routing, and decides on the controller that will actually run. A path-prefix filter could be bypassed by writing the same URL differently, such as `/%61pi/...`. Tests try those spellings.
- **Staff-only operations.** Marking a no-show can cost a patient their deposit, and the audit trail reveals activity across patients. Both are closed to the agents.
- **Everything goes through the patient.** Appointment routes include the patient, and a mismatch answers `404`, so a manipulated agent cannot confirm that someone else's appointment exists. Patients can only be found by exact identifier or phone number, and there is no way to list them.
- **Proposals cannot dictate outcomes.** Requests have no price, status or deposit fields, and unknown fields are rejected rather than ignored.
- **Errors carry fixed text.** Responses use a stable code and a fixed message, and never repeat request data, because a model might read them and act on them.
- **Access leaves a trace.** Reading patient data and every refused request are recorded, without the data itself.

## Consequences

- A manipulated agent can still act on any patient whose identifier or phone number it holds. Binding each conversation to a verified patient belongs to the agents service and is planned for Phase 2.
- Static tokens have no expiry or rotation. That is acceptable for two internal callers on a private network, and would not be for a public API.
- There is no rate limiting yet. It is planned for Phase 5, with the attack tests.
