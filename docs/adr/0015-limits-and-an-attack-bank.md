# 15. Limits per caller, and an attack bank against the real stack

- **Status:** Accepted
- **Date:** 2026-10-31
- **Author:** Ignacio Rodriguez Caro

## Context

Everything so far assumed that callers behave in volume. A runaway or manipulated agent could flood the core, and each message to the agents spends a share of a paid plan. Separately, tests written next to the code tend to check what its author already thought of, and claims about security are worth little without something that keeps them true.

## Decision

**Limits live in memory, per caller.** The core runs as one process, so it counts per token in windows of one minute with no new dependency. Losing the counts on a restart costs a minute of protection. The agents service limits messages per conversation, and conversations per phone and in total.

**Wrong tokens count per address, and are checked before the token.** If a right guess were let through during the wait, the wait would tell it apart from the wrong ones.

**A flood costs little.** The first refusal of each minute goes to the audit trail, and the rest only to a counter, or attackers could fill the trail on purpose.

**No limit stands before an emergency.** Triage runs first, and a message with an emergency is answered even over the limit or while another message of the same conversation is in progress.

**The attacks run against the running stack.** The real core and console over HTTP, not mocks. A result is what was observed: an attack that gets through, gets an error 500 or cannot finish counts as a breach, so a stack that is down cannot produce a clean report. The bank is checked by removing real defences one at a time and confirming that it notices.

**The report is compared in CI.** It holds nothing that varies between runs, so a change in what stops an attack fails the build until someone reviews it.

**A matrix decides every route.** Routes come from Spring and permissions from a table written by hand, so a new route fails until someone decides who may use it.

**Attacks on the model assume the worst.** A script plays a model that does whatever the attacker wants, which measures the code around the model at no cost. How well the real model resists is a separate question, for the evaluations.

## Consequences

- Behind one proxy, all callers share an address. So wrong tokens through the console can make its users wait for up to a minute. Tokens are long and random, so this limit protects logs and CPU more than the tokens, and an attacker able to reach the console could cause that wait.
- Limits are per process, so several instances of the core would each apply their own.
- Windows are fixed, so a caller can reach twice the limit across the turn of a minute. That is enough to stop a flood, which is the point.
- Triage still matches text, so an emergency written with look-alike letters from another alphabet would get past it, unlike invisible or wide characters, which are normalized.
- The bank takes a couple of minutes, because each flood needs a minute of its own.
- The rate limits are not per patient, so a manipulated agent could still act on many patients within its limit of requests. The audit trail records that it did.
