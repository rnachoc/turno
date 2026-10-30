# 8. No API budget: the author's Claude subscription only

- **Status:** Accepted
- **Date:** 2026-10-30
- **Author:** Ignacio Rodriguez Caro

## Context

Turno is a portfolio project with no budget. The author has a Claude subscription, which does not include API credit. Anthropic's [legal and compliance page](https://code.claude.com/docs/en/legal-and-compliance) and [support article](https://support.claude.com/en/articles/15036540-use-the-claude-agent-sdk-with-your-claude-plan) draw these lines:

- Signed in with a plan, `claude -p` and the Agent SDK draw from that plan's limits, which assume ordinary individual use.
- Developers may not route requests through a plan's credentials on behalf of their users. Products should use API keys.

So the author can run Turno on their own machine with their own login, but a public service that answers visitors through that login is not allowed.

## Decision

- **Model access goes through one small interface**, with two implementations: a scripted model for tests and CI, and a client for the official, unmodified `claude -p`, signed in by whoever runs it. Adding an API implementation later would touch nothing else.
- **The model gets no tools from the CLI.** The agent reads untrusted text and runs on a real computer, so every Claude Code tool, hook, MCP server and setting is disabled, and it runs in an empty folder. It can only return a proposal in a fixed schema, which fits [ADR 2](0002-the-model-proposes-the-code-decides.md). This is verified by a test before any agent is built on it.
- **CI never calls a model.** It has no credentials and costs nothing.
- **The regression gate works on recorded runs.** The author runs the scenario bank locally and commits the results, tagged with a hash of the prompts, tools and model. CI fails if those changed without a new recording, or if the recorded metrics got worse.
- **There is no public live demo.** It is replaced by a static replay of real recorded conversations, a video, and instructions to run Turno locally with your own Claude login.
- **Everything else stays free:** open-source services in Docker, local speech models, GitHub Actions on a public repository, and static hosting for the replay.

## Consequences

- The scenario bank's size is limited by the plan's usage limits, so it runs in small batches. Published numbers will state how many runs they come from.
- "Cost per booking" can only be an estimate of the API-equivalent price, from the figure the CLI reports. No money is actually spent, and the report will say so.
- Voice latency includes starting a process for each turn, so it will be worse than a direct API connection. It will be measured and published as it is.
- A visitor cannot try Turno in one click, and the replay has to make up for that.
- Anyone who wants a real deployment needs their own API key and commercial terms.
