# Case study: an AI front desk where the model proposes and the code decides

Turno is a portfolio project: AI agents that run the front desk of a fictional clinic. All data is synthetic, nothing here has met a real patient, and no message leaves the system. This records what was measured and what went wrong, so that the claims can be checked.

## The bet

A language model reads untrusted text for a living, so it will be fooled sooner or later. Rather than trying to make that impossible, Turno makes it cheap: a fooled model can only produce a proposal, which a transactional core judges like any other. There is no price field for it to set, no tool that refunds, and no way of naming another patient. Emergencies are answered by fixed rules before any model is involved.

## What was measured

Figures from one run per scenario, so a difference of a scenario or two between models may be chance.

| Model | Scenarios passed | Median call | Est. cost per scenario, on the API |
| --- | ---: | ---: | ---: |
| Haiku | 49 of 50 | 4.4 s | $0.0028 |
| Sonnet | 48 of 50 | 5.1 s | $0.0292 |
| Opus | 46 of 50 | 3.3 s | $0.0600 |

The most expensive model did not win. On this bank it failed more often than the cheapest, at about twenty times the cost. That is not evidence that it is worse, but it is reason enough not to pay for it here. See the [matrix](../agents/evals/MATRIX.md), and read what each model really said in the [replay](replay/index.html).

The one scenario Haiku fails is left failing, because the agent did nothing wrong: the simulated patient asked for a person. Changing the scenario until it passes would make the number better and less true.

92 attacks run against the real stack on each build, and [their report](../agents/security/REPORT.md) says what stopped each one. CI fails if what stops an attack changes.

A judge reads what facts cannot show. On 29 conversations labelled by hand it reached full agreement, but those labels were written by the author of the system, so this measures it against one person's reading of each question. [It decides nothing](../agents/evals/JUDGE.md).

## What testing found

The figures matter less than what producing them turned up. These all got past ordinary tests:

- **Invisible characters hid an emergency.** A zero-width character inside "no puedo respirar" got past triage, which reads text, and reached the model. An attack in the bank found it.
- **"Sigo sin poder respirar, me muero" got no emergency number.** A chaos test sent it while checking something else.
- **An emergency could get a "conversation busy" error** if it arrived while the model was answering an earlier message.
- **Patients who chose not to book were handed over to a person.** Telling them "no he reservado nada" tripped the guard on false claims, which only recordings with the real model showed.
- **A NUL character in an email or in a filter caused an error 500**, and half a Unicode character was stored in a name.
- **The attack bank itself had a hole.** With the limit per caller removed, a flood still looked stopped, because an earlier attack had blocked the address. Removing real defences one at a time is what showed it.
- **Graders and judges were wrong too.** One grader failed the agent for saying "your appointment is not cancelled", and the judge's first review flagged nine honest handovers because nobody had told it they happened.

## What this does not show

- It says nothing about real patients, real clinics or real load.
- Each scenario ran once per model, so small differences mean little.
- The patient is simulated by a model, which is more articulate and more patient than people are.
- WhatsApp and email stop at a simulated gateway, because there are no provider accounts. Voice does not exist yet.
- Costs are what the API would charge. This ran on a subscription.
- Triage matches text, so an emergency written with look-alike letters from another alphabet would get past it.

## How it was built

Java 25 and PostgreSQL for the core, with integrity enforced by database constraints rather than application checks. Python for the agents, through the Claude CLI on a subscription, with no API budget. Temporal for slot refill, deciding nothing itself. Angular for the console, under a policy that runs only its own files. Each decision is recorded in [`docs/adr`](adr).

Tests were checked by mutation: breaking the code on purpose, and treating a test suite that does not notice as the bug. Everything is replayed from a clean clone on Linux.
