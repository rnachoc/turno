# 5. One YAML file per tenant

- **Status:** Accepted
- **Date:** 2026-10-30
- **Author:** Ignacio Rodriguez Caro

## Context

Adapting Turno to another clinic should be a configuration change, not a code change. Hours, treatments, prices and policies differ between clinics, while the rules that apply them do not.

## Decision

Each clinic is one file in `config/tenants/`. A language-neutral JSON Schema validates its shape in CI. Rules that a schema cannot express, such as a practitioner referencing an unknown treatment or overlapping opening hours, are validated by the core when it loads the file. Money is stored as integer cents to avoid rounding errors.

## Consequences

- A new clinic is a reviewable, validated pull request.
- Validation lives in two places, schema and core, with a clear split between shape and meaning.
