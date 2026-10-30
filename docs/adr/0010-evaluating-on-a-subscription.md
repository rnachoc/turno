# 10. Evaluating the agent on a subscription

- **Status:** Accepted
- **Date:** 2026-10-30
- **Author:** Ignacio Rodriguez Caro

## Context

The agent's rules are tested deterministically, but no unit test says whether the real model books the right thing, invents a price or falls for an attack. That takes running it for real, and [ADR 8](0008-no-api-budget.md) rules out an API budget and any model in CI.

## Decision

**Fifty scenarios, in files.** A scripted scenario sends fixed messages, which is cheap and suits attacks and emergencies. Any other has a simulated patient, played by the same isolated model, pursuing a goal.

**Graders check facts only.** They read what the core stored, and whether the amounts, times and doctors in the replies exist. They use no model, so a grade never depends on another model's opinion, and CI can repeat it. They cannot judge tone or helpfulness: a calibrated judge belongs to Phase 5.

**A failure of the plan is not a failure of the agent.** If the model fails, for instance on reaching the plan's limit, the run stops and records nothing for that scenario.

**Recordings are checked before being written.** The model's context holds the account's email and the machine's paths, so each string of a recording is checked, and the recording is refused if it holds an email outside `.example`, a system path or a token.

**The gate works on recordings.** CI grades them again, with no model and no core, and fails if:

- a recording is stale, because the prompts, the schema, the model or the scenario changed;
- a scenario that passed now fails;
- a recording or a scenario in the baseline disappeared, so deleting a failure is not a way out;
- a recording was not accepted on purpose;
- the published report does not match the recordings.

A known failure can be accepted, but only explicitly, and it stays listed in the public report.

## Consequences

- **The fingerprint leaves out the agent's code**, on purpose. A change to the loop or to the guards does not make recordings stale, because re-recording for each refactor is not affordable on this plan. Unit tests cover those, which means that a change in the agent's code can alter its real behaviour with the gate unaware. A deliberate re-recording before a release closes that gap.
- **Editing a prompt invalidates the whole bank**, so prompts are expensive to change. That is honest, as a new prompt really is untested.
- **Each scenario runs once.** A pass can be luck and a failure can be noise, and the report says so. Scenarios that fail will be repeated to tell which it is, rather than repeating all of them.
- The gate compares against what was accepted, so it stops regressions but it does not prove that the bank covers enough. A scenario can only catch what someone thought of.

## What the pilot showed

A pilot of six scenarios took 28 model calls. Scenarios with a simulated patient took 8 or 9 calls and scripted ones took at most one, which puts the full bank near 280 calls rather than the 600 first estimated. The pilot also found a real bug: "tengo la cara muy hinchada" was not treated as an emergency, because the rule only matched the opposite word order. The model handed it over to a person, but without giving the emergency number.
