# 6. Booking integrity lives in the database

- **Status:** Accepted
- **Date:** 2026-10-30
- **Author:** Ignacio Rodriguez Caro

## Context

A clinic's worst failure is two patients in one slot. Requests arrive concurrently, agents retry when a call times out, and a check in application code followed by an insert always leaves a gap between the two.

## Decision

**Double bookings.** Two PostgreSQL exclusion constraints reject overlapping active appointments, one per practitioner and one per patient. Ranges are half-open, so an appointment may start exactly when the previous one ends. Inserts use `ON CONFLICT DO NOTHING`, which makes concurrent requests wait for the winner instead of deadlocking with it.

**Holds.** A hold is an appointment with status `HOLD` and an expiry. Constraints cannot read the clock, so a hold that has run out still occupies its row until something marks it. Reads ignore it from the instant it expires, and any operation that needs the slot marks it first, in its own transaction. A background job only keeps the audit trail up to date, and no rule depends on it.

**Retries.** Every write needs an `Idempotency-Key`. The key is claimed in the same transaction as the operation: a concurrent duplicate blocks until the first request finishes and then replays its response. A refused operation rolls back together with its key, so its retry is judged again rather than replaying a stale refusal. Using a key for a different request is an error.

**Time.** Every rule reads an injected clock and receives "now" as a parameter, including in SQL. Slots that touch a daylight saving change are not offered, because a patient could not be told an unambiguous time.

**Audit.** Accepted actions are recorded in the same transaction, so an action and its entry stand or fall together. Refusals are recorded afterwards, once the refused transaction has rolled back. A trigger rejects any update, delete or truncate of the trail.

**Catalogue.** Treatments and practitioners stay in the tenant file ([ADR 5](0005-one-yaml-file-per-tenant.md)). Each appointment copies its price, deposit and times at booking, so a later change of prices does not alter an existing booking.

## Consequences

- A bug in the services cannot create a double booking. Tests prove this by attacking the database directly.
- The core depends on PostgreSQL features, so the database engine is not interchangeable, and tests must run on the real one.
- Moving an appointment with a plain `UPDATE` can still deadlock with another move. PostgreSQL resolves it by aborting one of them, and the API answers `CONCURRENT_CONFLICT` so that the caller retries.
- The service connects as the database owner, so a compromised core could disable the audit trigger. A separate role with fewer privileges is planned for Phase 5.
