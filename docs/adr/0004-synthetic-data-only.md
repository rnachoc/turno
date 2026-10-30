# 4. Synthetic data only

- **Status:** Accepted
- **Date:** 2026-10-30
- **Author:** Ignacio Rodriguez Caro

## Context

A clinic handles health data, a special category under the GDPR. Turno is a public demonstration, and it sends conversations to external model providers.

## Decision

Turno only ever runs on synthetic data. The rule is enforced where it can be: the tenant schema requires `fictional: true` and an email address on the reserved `.example` domain (RFC 2606). The design still treats patient data as sensitive, with masking before it reaches a model, consent checks and an audit trail in the core.

## Consequences

- The repository, the demo and the evaluation data can all be public.
- A real deployment would need more work that is out of scope here: a legal basis, a data processing agreement and a review of each provider.
