# 16. Channels in front of the same agent, and nothing believed before a signature

- **Status:** Accepted
- **Date:** 2026-10-31
- **Author:** Ignacio Rodriguez Caro

## Context

Offers reached nobody ([ADR 12](0012-durable-workflows-decide-nothing.md)), and patients could only write through a test page. A real clinic gets WhatsApp and email, which adds three problems: anyone can send anything to a public address, providers repeat deliveries, and an email is written by whoever wants, including hidden parts a person never sees. This project has no budget or accounts for a provider.

## Decision

**One agent, several channels.** A message from WhatsApp or email goes through the same triage, guards and consent as the web chat. The channels only translate.

**Nothing is believed before its signature.** Webhooks check an HMAC of the exact bytes delivered, in the format Meta uses, and they do not exist unless a secret is set. Each provider's identifier is heard once, and old deliveries are ignored, so a captured yes cannot count twice.

**The address binds the conversation, as a phone does in the web chat.** Nothing a message says can change who it is about.

**An email counts only if the provider verified its sender.** Anyone can write any address in an email. So an unverified one is dropped, without an answer, which would go to whoever is being impersonated. Only the plain text and the subject are read, because an HTML part can hide from people what it tells a model. Older messages quoted below a reply are left out, or each answer would be read back as if the patient had written it.

**An offer is announced in the transaction that makes it.** A row in the core, like freed slots, so it can never exist without its notice. The text is written by the code from facts given by the core. A yes follows the path of any consent, and confirms that hold and nothing else. A clear no releases it at once, with no model, so the next person need not wait for it to run out.

**The patient's reply is handled in the background.** A provider will not wait for a model, and messages of one sender take turns. An emergency does not: it is answered at once, over any limit and while another message is in progress, even from a stranger or with the core down.

**The gateway is simulated, on purpose and in plain sight.** It writes what would have been sent to a file. Everything up to that point is real and tested.

## Consequences

- Nothing leaves this system yet. Connecting a provider means implementing one small interface, plus Meta's setup handshake, which is not here.
- A notice is sent at least once: a crash between sending and recording it repeats the message. Losing it would be worse.
- The conversations are in memory, so a restart forgets a pending offer. A later yes then goes to the agent, which can find the hold and ask for consent again.
- Answering emails by address means two people sharing one identify nobody, and get told so.
- Over the limit, a sender gets silence, unlike the web chat's error, since a message per message would be spending what the limit protects.
- Registering from WhatsApp trusts the profile's name as far as the core's validation does. It is only a name, and the model never reads it.
