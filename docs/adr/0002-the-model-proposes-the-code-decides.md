# 2. The model proposes, the code decides

- **Status:** Accepted
- **Date:** 2026-10-30
- **Author:** Ignacio Rodriguez Caro

## Context

The agents read untrusted text: calls, WhatsApp messages and emails can all contain instructions written to manipulate them. Language models cannot reliably tell data from instructions, so prompts alone cannot protect patient data or money.

## Decision

Agents have no direct access to the database or to payments. Each state-changing action is a proposal to the core API, which validates rules, permissions and consent, and then executes it or rejects it. Anything that moves money also needs human approval. Urgency rules live in code; the model can only raise a signal.

## Consequences

- A successful prompt injection can at most produce a proposal, and the core judges that proposal like any other.
- Safety can be tested deterministically by calling the core directly, with no model involved.
- Every action needs an explicit API and adds a network hop.
