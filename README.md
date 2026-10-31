# Turno

[![CI](https://github.com/rnachoc/turno/actions/workflows/ci.yml/badge.svg)](https://github.com/rnachoc/turno/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

A team of AI agents that answers the phone, WhatsApp and email of a small clinic and takes care of its admin work, with measurable proof that it works.

**The model proposes, the code decides.** No agent can write to the database or move money. Agents propose actions to a transactional core, which enforces rules, permissions and consent. So a malicious email can ask for a refund, but it has no way of getting one.

> **Status: early development.** You can book an appointment by chatting with a text agent, on top of a core with patients, availability, holds, cancellation rules and an append-only audit trail. When someone cancels, a durable workflow offers the slot to the waitlist, and the patient can answer by WhatsApp. The clinic has a console with an agenda, a daily report worked out from the records, a queue of decisions and the trace of each conversation. The evaluation harness exists and its [report](agents/evals/REPORT.md) says how much has been recorded so far. WhatsApp and email work up to a simulated gateway, because this project has no provider accounts, so **nothing leaves it yet. Voice, general consent tracking and payments do not exist**, and the core only records whether a deposit is forfeited without charging anything; see the [roadmap](#roadmap). Measurements are published as they are made, good or bad: the [evaluations](agents/evals/REPORT.md), the [comparison of models](agents/evals/MATRIX.md), the [judge](agents/evals/JUDGE.md) and the [attacks](agents/security/REPORT.md).

> **Synthetic data only.** The clinic in this repository is fictional. Never use Turno with real patient data.

## Architecture

```mermaid
flowchart TD
    channels["Channels<br/>voice · WhatsApp · email · web"] --> agents
    agents["Agents service<br/>Python · FastAPI · Claude"] -- "proposes actions" --> core
    core["Core: the code decides<br/>Java · Spring Boot<br/>availability · rules · consent · audit"] --> db[("PostgreSQL")]
    flows["Durable workflows<br/>Temporal"] -- "what happens next?" --> core
    console["Console<br/>Angular"] -- "staff's API only" --> core
    owner["Owner's assistant<br/>MCP, figures only"] -- "reads" --> core
    evals["Evaluation bank<br/>simulated patients · attack tests · CI gate"] -.-> agents
```

This is the target design. Today, `core`, PostgreSQL, a text agent over web chat, the slot refill workflow and the console work. The decisions behind the design are recorded in [`docs/adr`](docs/adr).

## How the core stays correct

| Risk | What stops it | Where |
| --- | --- | --- |
| Two patients in one slot, even under concurrent requests | PostgreSQL exclusion constraints, not application checks | [ADR 6](docs/adr/0006-booking-integrity-in-the-database.md) |
| An agent retries after a timeout and books twice | Idempotency key claimed in the operation's own transaction | [ADR 6](docs/adr/0006-booking-integrity-in-the-database.md) |
| A daylight saving change creates an ambiguous appointment | Slots touching the change are not offered | [`SlotCalculator`](core/src/main/java/io/github/rnachoc/turno/core/availability/SlotCalculator.java) |
| A manipulated agent sets its own price | Requests have no price field, and unknown fields are rejected | [ADR 7](docs/adr/0007-api-authentication-and-boundaries.md) |
| A manipulated agent reaches another patient's appointment | Routes go through the patient, and a mismatch answers `404` | [ADR 7](docs/adr/0007-api-authentication-and-boundaries.md) |
| A manipulated agent charges a no-show | Staff-only operation, checked after routing | [ADR 7](docs/adr/0007-api-authentication-and-boundaries.md) |
| Someone rewrites history | The service's database role cannot change the audit trail, and a trigger stops even the owner | [ADR 11](docs/adr/0011-least-privilege-in-the-database.md) |
| The model books or cancels on its own | The code asks in its own words, and only a clear yes from the patient runs the stored action | [ADR 9](docs/adr/0009-the-agent-loop.md) |
| A message moves the conversation to another patient | The model never sees or chooses the patient | [ADR 9](docs/adr/0009-the-agent-loop.md) |
| A message orders the model to run commands | The model has no tools and runs with nothing to load, which real attacks verify | [ADR 8](docs/adr/0008-no-api-budget.md) |
| A workflow breaks a rule while refilling a slot | It decides nothing: the core picks each patient, and the worker's token reaches nothing else | [ADR 12](docs/adr/0012-durable-workflows-decide-nothing.md) |
| A crash repeats an offer or loses a slot | Freed slots are written with the cancellation, and Temporal resumes from its history | [ADR 12](docs/adr/0012-durable-workflows-decide-nothing.md) |
| A patient's words end up in the clinic's records | The core only accepts codes in what the agents report, which a pattern enforces | [ADR 13](docs/adr/0013-the-clinic-reads-from-the-core.md) |
| A script in the console steals the staff's token | The token stays in memory, and the policy lets the page run only its own files | [ADR 13](docs/adr/0013-the-clinic-reads-from-the-core.md) |
| A runaway or manipulated caller floods the service | Limits per token, per conversation and per phone, which never stand before an emergency | [ADR 15](docs/adr/0015-limits-and-an-attack-bank.md) |
| A new route ends up open by accident | Tests fail until a table says who may use it | [ADR 15](docs/adr/0015-limits-and-an-attack-bank.md) |
| Someone writes in a patient's name | Webhooks check the provider's signature, deliveries count once, and an email counts only if its sender was verified | [ADR 16](docs/adr/0016-channels-in-front-of-the-same-agent.md) |
| An email hides instructions for the model | Only its plain text is read, and it is still just a patient's message | [ADR 16](docs/adr/0016-channels-in-front-of-the-same-agent.md) |
| A patient's name steers the owner's assistant | The owner's tools return figures, never a name | [ADR 17](docs/adr/0017-the-owner-asks-through-mcp.md) |
| Someone describes an emergency | Fixed rules answer first with the emergency number, without the model | [ADR 9](docs/adr/0009-the-agent-loop.md) |

Every attack in the [security report](agents/security/REPORT.md) runs against the real stack on each build, and the report says what stopped it. Known limits are listed in those records.

## Quick start

You need Docker with Compose.

```bash
cp .env.example .env
docker compose up --build --detach --wait
```

```bash
curl http://127.0.0.1:8080/actuator/health   # core
curl http://127.0.0.1:8000/health            # agents
```

The Temporal UI, with the running workflows, is at http://127.0.0.1:8233.

### Write through WhatsApp

With `make agents` running, this delivers a signed message to the real webhook. Answers go to `simulated-outbox.jsonl` in `agents/`.

```bash
cd agents && uv run --locked --env-file ../.env python demo/whatsapp.py "Quiero una cita"
```

### Ask from your assistant

The owner's MCP server gives an assistant such as Claude Desktop the clinic's figures, and nothing else. With the stack up, add this to its MCP configuration, using the token from your `.env`:

```json
{
  "mcpServers": {
    "turno": {
      "command": "uv",
      "args": ["run", "--locked", "--project", "/path/to/turno/agents", "python", "-m", "turno_agents.mcp_server"],
      "env": { "TURNO_API_TOKEN_STAFF": "..." }
    }
  }
}
```

Then ask how many no-shows there were this month, and which treatment loses the most appointments.

### Look at the console

The console is at http://127.0.0.1:4200 and asks for `TURNO_API_TOKEN_STAFF` from your `.env`. To give it something to show, with synthetic data only:

```bash
make demo-data
```

### Look at the metrics

With the stack started by `make up-observability`, Grafana is at http://127.0.0.1:3000, with a dashboard of bookings, handovers, latency and estimated cost. It needs an image of over 2 GB, so the normal stack leaves it out.

### Talk to the agent

The agent uses the official [Claude Code](https://code.claude.com/docs/en/setup.md) CLI, signed in with your own Claude plan, so it runs on your machine rather than in a container ([ADR 8](docs/adr/0008-no-api-budget.md)). With the stack up and `claude auth login` done:

```bash
make agents
```

Then open http://127.0.0.1:8001 and ask for an appointment. Everything is synthetic: do not enter real personal or health data.

### Book an appointment through the API

This walks through the core API, with no model involved. It needs [jq](https://jqlang.org/). On macOS, replace `date -d '+7 days'` with `date -v+7d`.

```bash
set -a; . ./.env; set +a
API=http://127.0.0.1:8080/api/v1
AUTH="Authorization: Bearer $TURNO_API_TOKEN_AGENTS"
post() { curl -s -X POST "$API$1" -H "$AUTH" -H "Idempotency-Key: $(date +%s%N)" -H 'Content-Type: application/json' -d "${2:-{\}}"; }

# Register a synthetic patient
PATIENT=$(post /patients '{"fullName":"Laura Test","phone":"+34600111222","language":"es"}' | jq -r .id)

# Find the first free slot for a check-up in the coming week
SLOT=$(curl -s -H "$AUTH" "$API/availability?treatmentId=check-up&from=$(date +%F)&to=$(date -d '+7 days' +%F)" | jq -c '.[0]')

# Hold it for five minutes, then confirm it
HOLD=$(post "/patients/$PATIENT/appointments" "$(jq -c '{practitionerId, treatmentId, startsAt}' <<< "$SLOT")" | jq -r .id)
post "/patients/$PATIENT/appointments/$HOLD/confirm" | jq '{status, startsAt, priceCents}'
```

## Development

| Module | Requirements | Test command |
| --- | --- | --- |
| `core/` | JDK 25 and Docker (tests run on a real PostgreSQL) | `./mvnw verify` |
| `agents/` | [uv](https://docs.astral.sh/uv/) | `uv run pytest` |
| `agents/`, against the real core | The stack running | `make test-contract` |
| `agents/`, against the real model | The stack running and a Claude login; spends your plan and never runs in CI | `make test-live` |
| Slot refill, end to end | The stack running with `TURNO_TENANT=e2e-clinic`, whose contact hours never make it wait | `make test-e2e` |
| `console/` | Node 24 | `npm test` |
| Attack bank and security report | The stack running; takes a couple of minutes and spends nothing | `make test-attacks` |
| Evaluations: record | The stack running and a Claude login; spends your plan | `make eval ARGS="--limit 10"` |
| Evaluations: gate | Nothing else: no model and no core | `make eval-gate` |

The `Makefile` wraps these commands (`make up`, `make test`, `make lint`) on systems with `make`.

## Repository layout

| Path | Contents |
| --- | --- |
| `core/` | System of record: availability, bookings, rules and audit |
| `agents/` | AI agents and their tools |
| `console/` | What the clinic's staff use: report, agenda, decisions and traces |
| `config/tenants/` | One YAML file per clinic, validated against `config/tenant.schema.json` |
| `observability/` | The Grafana dashboard |
| `docs/adr/` | Architecture decision records |

## Roadmap

- [x] **Phase 0: Foundations.** CI, secret scanning, Docker Compose, ADRs, synthetic clinic.
- [x] **Phase 1: Core.** Availability engine, bookings with holds, cancellation rules, audit. Double-booking and idempotency tests.
- [x] **Phase 2: Text agent.** Receptionist and scheduler over web chat, with guardrails and rule-based triage.
- [x] **Phase 3: Evaluations.** Scenario bank, patient simulator, deterministic graders, regression gate over recorded runs ([ADR 8](docs/adr/0008-no-api-budget.md)).
- [x] **Phase 4: Durable workflow and console.** Slot refill with Temporal ([ADR 12](docs/adr/0012-durable-workflows-decide-nothing.md)) the daily owner report, the console with its queue of decisions and the traces are done ([ADR 13](docs/adr/0013-the-clinic-reads-from-the-core.md)). Metrics go to an optional Grafana ([ADR 14](docs/adr/0014-metrics-from-the-core.md)).
- [ ] **Phase 5: Security.** Rate limits and an attack bank with a published report are done ([ADR 15](docs/adr/0015-limits-and-an-attack-bank.md)). The calibrated judge and the model matrix are built ([judge](agents/evals/JUDGE.md), [matrix](agents/evals/MATRIX.md)).
- [ ] **Phase 6: Voice.** Streaming pipeline with barge-in and measured latency.
- [ ] **Phase 7: Channels and MCP.** WhatsApp and email up to a simulated gateway ([ADR 16](docs/adr/0016-channels-in-front-of-the-same-agent.md)) and the owner's MCP server ([ADR 17](docs/adr/0017-the-owner-asks-through-mcp.md)) are done. Still to come: connecting a real provider, which needs accounts.
- [ ] **Phase 8: Release.** Public replay of recorded conversations, video, case study with real numbers.

## Author

Ignacio Rodriguez Caro · [rnachoccontact@gmail.com](mailto:rnachoccontact@gmail.com)

## License

[MIT](LICENSE)
