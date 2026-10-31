# 13. The clinic reads everything from the core

- **Status:** Accepted
- **Date:** 2026-10-31
- **Author:** Ignacio Rodriguez Caro

## Context

The owner needs a daily report and the staff need a console. Part of what they must see lived only in the memory of the agents service: conversations handed to a person, and what each conversation cost. A restart would lose a patient waiting for a call, and a console would need two backends, one of them on the owner's laptop because the agents run there ([ADR 8](0008-no-api-budget.md)).

## Decision

**The agents report what they did to the core.** Each step of a conversation is sent with its position, and the core ignores positions it already has, so a retry changes nothing and cannot reopen a handover someone has resolved. A handover step opens an item in the staff's queue.

**Codes only.** The core validates each step against a pattern that admits codes and model names, never spaces. So neither a bug nor a manipulated agent can copy what a patient wrote into this record. Patients' words stay in the memory of the agents service.

**A separate thread does the reporting.** It cannot delay or break a reply, which matters because an emergency has to be answered even with the core hanging. It retries for as long as the process lives, since a handover caused by a failure of the core is just what the clinic needs to know about when the core returns.

**The report is worked out when asked for.** It runs queries over the appointments, the audit trail and the steps, inside one snapshot, with no model and no stored copy. Every figure can be traced to rows. A past day can still change, for example when someone marks a no-show the next morning, so the report says when it was generated.

**The console has one backend and runs only its own files.** It is served from the same origin as the part of the API it passes on, so the core needs no CORS. Nginx forwards the staff's routes and the clinic's description, and nothing else of the API or of the actuator. The staff's token stays in the page's memory, and a Content-Security-Policy with no inline code and no other origin means a script cannot get in to read it. All styles live in one file, because Angular would otherwise insert them inline, which that policy forbids.

## Consequences

- If the agents service stops while the core is down, the steps it had not sent are lost, a handover among them. Keeping them would need a queue on disk, which is not worth it for a process that runs on a laptop.
- If the core refuses a report, those steps are logged as an error and skipped, because the same request would get the same answer and would block everything behind it.
- A step's time is when the core heard about it, so during an outage the steps count on the day the core returned. This keeps the agents from choosing the date of a record.
- Reloading the console asks for the token again. A real deployment would put the clinic's single sign-on in front instead of a shared token.
- Cost is an estimate of what the API would charge. The plan in use has no cost per call.
- "Today" and every day boundary follow the clinic's time zone, so a day can last 23 or 25 hours.
- Dates more than ten years from today are refused, which also closes a failure in availability, where a date at the limit of the calendar caused an error 500.
- The approval queue holds what the code cannot decide: handovers, and appointments that have started and need someone to say whether the patient came, since that decision can lose them a deposit.
