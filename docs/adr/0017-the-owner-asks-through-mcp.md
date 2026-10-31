# 17. The owner asks through MCP, which only reads figures

- **Status:** Accepted
- **Date:** 2026-10-31
- **Author:** Ignacio Rodriguez Caro

## Context

An owner wants to ask things like how many no-shows there were this month and which treatment loses the most appointments. Building a question box means a model, and paying for it. MCP lets the owner use the assistant they already have, such as Claude Desktop, with the clinic's data as a tool.

That assistant is not ours, though. It may have other tools connected, such as email or files, and anything a tool returns can act as instructions to it.

## Decision

**Two tools, read-only, built on the reports.** One for a day and one for attendance over a period. The client has no method that writes, so a manipulated model has nothing to reach for.

**Figures only, never a patient's words or name.** A name is text anyone can choose when registering, so it could be written to steer whatever reads it. Excluding it protects patients and also the owner's assistant. A report of no-shows by treatment needs no names to be useful.

**Dates are checked before they leave.** A model chooses these values and may have been told what to choose, so anything other than a date never reaches the core.

**It runs on the owner's machine over standard input and output**, with the staff's token from the environment. There is no network port to protect.

## Consequences

- The owner cannot ask who missed an appointment here. The console answers that, with a person looking at it.
- The staff's token can do more than these tools use. A token limited to reports would be better, once there are enough tools to justify a fourth one.
- Answers depend on the owner's assistant quoting figures faithfully, which nothing here can check. The figures themselves can be checked against the console.
