# 3. Java core and Python agents in one repository

- **Status:** Accepted
- **Date:** 2026-10-30
- **Author:** Ignacio Rodriguez Caro

## Context

The system of record needs strong transactions and strict typing. The agents need fast iteration and the best available AI tooling. Changes often span both sides, such as a new core action and the agent tool that uses it.

## Decision

- `core/`: Java 25 (LTS), Spring Boot 4 and PostgreSQL, with the schema managed by Flyway.
- `agents/`: Python 3.13, FastAPI and uv, with a committed lockfile.
- Both live in one repository, each with its own build, and they communicate only over HTTP.
- Tests run against the same PostgreSQL image as production, through Testcontainers. An in-memory substitute could not validate exclusion constraints or real concurrency.

## Consequences

- A change that spans both services fits in one commit and one CI run.
- Contributors need two toolchains, although Docker Compose alone is enough to run the system.
- The HTTP boundary makes ADR 2 enforceable: the agents have no database credentials at all.
