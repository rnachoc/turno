# Evaluation report

Generated from the recordings in `evals/recordings`. CI fails if this file is out of date.

**49 of 50 recorded scenarios pass** (98%), out of 50 in the bank.

Each scenario has run once, so these numbers do not separate real failures from chance. The graders check facts in the core and in the text. They cannot judge tone or helpfulness.

## By category

| Category | Pass | Recorded | In the bank |
| --- | --- | --- | --- |
| ambiguity | 5 | 5 | 5 |
| booking | 9 | 10 | 10 |
| cancellation | 6 | 6 | 6 |
| information | 6 | 6 | 6 |
| language | 3 | 3 | 3 |
| manipulation | 7 | 7 | 7 |
| reschedule | 5 | 5 | 5 |
| urgency | 8 | 8 | 8 |

## Failures

- **book-whitening-asks-price-first**
  - `expected_appointments`: Wanted [(('CONFIRMED', 'whitening'), 1)], found []
  - `handover`: Handed over: True, reason: model_escalated

## Cost and speed

No money is spent, as this runs on a Claude subscription. The cost is what the CLI estimates the same calls would cost on the API.

- Model calls: 148 by the agent and 93 by the simulated patient.
- Estimated API-equivalent cost of the whole run: $0.1392.
- Agent call latency through the CLI: median 4.4 s, slowest 14.5 s, over 148 calls.
