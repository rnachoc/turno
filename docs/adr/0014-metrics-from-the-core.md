# 14. Metrics come from the core and stay optional

- **Status:** Accepted
- **Date:** 2026-10-31
- **Author:** Ignacio Rodriguez Caro

## Context

Bookings, handovers, latency and cost should be visible over time, not only day by day. The agents service runs on a laptop with the owner's Claude login, so instrumenting it would need a second exporter and a collector within its reach, while everything it does already reaches the core ([ADR 13](0013-the-clinic-reads-from-the-core.md)).

## Decision

**The core is the only source.** Every operation goes through the audit trail, so one counter there covers them all, and the steps the agents report provide tokens, estimated cost and model latency. The agents service needs no instrumentation.

**Counted after the commit.** What rolls back was never done, so it must not count, and a retry answered from the idempotency store runs nothing and counts nothing.

**Labels are closed lists.** Only values from the code or from a fixed list become labels, and anything else from an agent counts as `other`. So a manipulated agent cannot create a series per value, and no label can hold an identifier or a phone number. Routes are labelled by their template.

**OTLP, to one optional container.** The core pushes with OpenTelemetry's protocol to `grafana/otel-lgtm`, which holds the collector, Prometheus and Grafana. That image weighs over 2 GB, so it only starts with `make up-observability`, and the core exports nothing unless asked. The dashboard is a file in this repository.

## Consequences

- Measuring how long a whole turn takes, as opposed to each model call, would need the agents service to report it.
- Counters are small at the pace of a clinic, and Prometheus reads growth between samples, extrapolating. So the dashboard is for trends and rounds its counts. The exact figures are in the daily report, which counts rows.
- The main series exist from startup at zero, because Prometheus cannot see the growth of a counter in its first sample. A rarer series can still lose its first event, which is why refusals also have one total per caller.
- Spring Boot needs `spring-boot-opentelemetry` on the classpath before it enables the OTLP exporter, even for metrics alone. Without it, the exporter silently does nothing.
- Grafana's default sign-in is left as it is, and it listens on `127.0.0.1` only. It holds no patient data.
