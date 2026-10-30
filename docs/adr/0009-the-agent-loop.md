# 9. The agent loop: what the code decides around the model

- **Status:** Accepted
- **Date:** 2026-10-30
- **Author:** Ignacio Rodriguez Caro

## Context

[ADR 2](0002-the-model-proposes-the-code-decides.md) says that the model proposes and the code decides. The core already rejects anything against its rules, but several things must also hold before a request reaches it: which patient the conversation is about, whether the patient really agreed, and what the patient gets to read.

## Decision

**The model answers one document.** Each answer is a reply and at most one action, from a closed list. No action has a patient, a price or a status among its fields, and extra fields are rejected. Appointment identifiers must be UUIDs, because they end up in a URL, where a crafted value could otherwise climb out of the patient's own path.

**The conversation is bound to one patient by the code.** The identifier is set when the conversation starts and it is never shown to the model, nor are the phone number and the name. Nothing in a message can move the conversation to someone else. Today the web chat takes the phone number from the form; a real channel would provide it, and verifying it belongs to whoever writes is still pending.

**Some changes need a yes that the model cannot give.** To confirm, cancel or move, the code stores the exact action and asks the patient in its own words, with the time, the price and any deposit at risk. If the next message is a clear yes, the code runs the stored action without calling the model. Anything else, such as "yes, but on Tuesday", changes nothing. So the model cannot book by itself, cannot swap the action afterwards, and cannot describe one thing while doing another.

**Urgency is decided first, with fixed rules.** Emergencies come before anything else, including the state of the conversation and the availability of the core, and they always get the emergency number. Questions about medication or diagnosis go to a person without calling the model. These rules favour false alarms, because a false alarm only costs a person's time.

**Replies are checked before they leave.** Email addresses, system paths and secrets are blocked for good, as is advice on medication. A claim that an appointment is booked or cancelled needs a read or a change in the core behind it, in the same turn. The model gets one more chance, with an explanation, and after that a person takes over.

**Everything is bounded.** Five model calls per message, two corrections, 1,000 characters per message and a daily limit on calls, all to protect the plan from a loop.

## What real testing changed

These came from running against the real CLI and the real chat, and no scripted test could have revealed them:

- The CLI rejected the schema, because Pydantic adds an OpenAPI keyword that is not JSON Schema.
- Windows has no time zone database, so `tzdata` is a dependency.
- An emergency written after the conversation had been handed over only got "a person will answer you soon", with no emergency number. This is why urgency now comes first.
- The model imitated the JSON quotes around its earlier replies, and it repeated a rejected sentence until it gave up, because the rejection did not say how to fix it.

## Consequences

- A manipulated model can write something unhelpful or wrong in its reply, but it cannot change an appointment without the patient's yes, reach another patient, or see who the patient is. Wrong facts in a reply, such as an invented price, are not caught by these rules. The evaluation bank of Phase 3 is what measures them.
- **The model still sees the account's email, the working folder, the operating system and the date.** The CLI adds them and no documented option removes them. The email is already public in this repository, and the output filter blocks both emails and paths.
- The urgency rules are keyword lists in two languages, so they will miss some wordings. They reduce the risk, but they are not a medical triage system. Turno runs on synthetic data only.
- Conversations are kept in memory, so they are lost on restart. Phase 4 adds durable workflows.
- Handing over only marks the conversation for now, because nobody is notified until the console exists in Phase 4.
- The agents service runs on the host, where the CLI is signed in. The container has no login, so it answers `MODEL_NOT_CONFIGURED` to conversations, which is a change from the scripted model planned at first: a scripted answer in a running service would look like a real agent that is not there.
- Each model call takes about five seconds through the CLI, so a message takes between 5 and 20 seconds.
