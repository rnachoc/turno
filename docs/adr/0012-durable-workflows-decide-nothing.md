# 12. Durable workflows that decide nothing

- **Status:** Accepted
- **Date:** 2026-10-31
- **Author:** Ignacio Rodriguez Caro

## Context

When a patient cancels, the slot should go to someone on the waitlist. That takes a sequence that lasts hours: offer it, wait for an answer, try the next person, and wait for the morning if it is night. A process holding that state in memory loses it on a restart, and hand-written timers in a database become a poor copy of a workflow engine.

## Decision

**Temporal does the waiting and the core does the deciding.** The workflow knows how to wait and whom to ask, and nothing else. At each step it asks the core what happens next. The core chooses the patient, checks the rules and the contact hours, and answers `OFFERED`, `WAIT`, `NO_CANDIDATE`, `LIMIT_REACHED`, `TOO_LATE` or `SLOT_TAKEN`. So the rules have a single home, and the workflow's history holds opaque identifiers rather than patient data.

**An offer is an ordinary hold.** It occupies the slot with a longer expiry, and accepting it is confirming it. So it goes through the rules already tested for confirmations, needs no new state in the appointment's life cycle, and cannot double-book.

**No Kafka.** The original design had it, but there is one consumer and one kind of event. Instead, a freed slot is a row written in the same transaction as the cancellation, and the worker polls for those rows. A crash between starting the workflow and acknowledging the row only repeats the attempt, and Temporal refuses a second workflow with the same identifier, so each slot gets exactly one. If more consumers appear, a broker can read the same table.

**The worker has its own token.** It can reach the workflow API and nothing else, and neither agents nor staff can reach that API.

**Temporal runs as a single container**, the development server with its state in a file on a volume. That keeps the stack small on a laptop, and it survives restarts, but it has no redundancy and it is not how this would run in production.

## Consequences

- A restart of the worker or of Temporal loses nothing. Tests rebuild a workflow from its history after losing its worker, and check that the offer is not made again.
- Answers are noticed by polling every minute, so a workflow ends up to a minute after an acceptance. This only affects when it stops, because the core is what ends an offer.
- Offers were only records at this point. See [ADR 16](0016-channels-in-front-of-the-same-agent.md).
- Joining the waitlist is what allows the clinic to contact a patient about a slot. General consent tracking still does not exist.
- A slot is offered as a whole: a treatment shorter than the freed slot leaves the rest unused until someone books it normally.
- Absences of a practitioner are still not modelled, so an offer could fall on one, as could any booking.
