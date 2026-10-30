# 1. Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-10-30
- **Author:** Ignacio Rodriguez Caro

## Context

Turno combines several languages, external providers and safety constraints. The reasons behind each choice are as valuable as the code, and they get lost if they only live in commit messages.

## Decision

Every significant decision is recorded as a short, numbered file in this folder, with its context, the decision and its consequences. An accepted record is never rewritten: if a decision changes, a new record supersedes it.

## Consequences

Anyone can understand why the system looks the way it does. Writing a record takes a few minutes per decision.
