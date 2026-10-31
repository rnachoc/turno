# 11. Least privilege in the database

- **Status:** Accepted. Supersedes the note on the owner's connection in [ADR 6](0006-booking-integrity-in-the-database.md).
- **Date:** 2026-10-30
- **Author:** Ignacio Rodriguez Caro

## Context

The service connected as the database owner. The audit trail's trigger stops accidents, but an owner can disable a trigger, so a compromised service could have rewritten the past. The audit trail is the evidence of what agents did, so it must survive the failure of the service that writes it.

## Decision

**The service connects as `turno_app`**, a role that owns nothing. It can read and write patients and appointments, append to the audit trail and manage idempotency keys, and nothing else. It cannot delete patients or appointments, change the schema, disable triggers or read the migration history. New tables get no privileges by default, so each migration grants what it needs on purpose.

**Migrations run in a separate step.** A one-shot `migrate` service applies them and exits, and the core only starts if it succeeded. Only this step knows the owner's password, so the running service does not have it at all: having a restricted role would mean little if the service could read the owner's password from its own environment. The step itself needs neither the API tokens nor the tenant file.

**Every integration test connects as `turno_app`**, as in production. So the suite proves that the role's privileges are enough, and separate tests attack it to prove that they are not too many.

**Requests have a size limit**, checked before reading them: 64 KB in the core and 16 KB in the agents service. A request of unknown length is refused, since it could otherwise send a body of any size.

## What real testing changed

The `migrate` step first exited successfully without having migrated anything. It uses lazy initialization so as not to need the tokens or the tenant file, and that left Spring's Flyway initializer unbuilt. Unit tests could not see it, because they enable Flyway in their own way. It only showed up when running the real step in Docker, where the core then found no tables. The step now calls Flyway explicitly, and a test runs the real profile against an empty database.

## Consequences

- A compromised service can still read and alter patients and appointments, and it can add false entries to the audit trail. What it cannot do is remove or change the entries already there.
- The owner's password still exists in the `.env` file of a Compose deployment, and in the `migrate` container while it runs. A real deployment would keep it in a secrets manager.
- The role's password must not contain a single quote, because it is written into a SQL statement.
- There was still no rate limiting at this point. See [ADR 15](0015-limits-and-an-attack-bank.md).
