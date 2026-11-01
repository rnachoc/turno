# 18. Voice as one more channel, on local engines

- **Status:** Accepted
- **Date:** 2026-11-01
- **Author:** Ignacio Rodriguez Caro

## Context

A clinic's patients mostly call. Voice adds what no text channel has: a transcription can be wrong, someone can talk over a question, and silence on a line feels broken. This project has no budget for speech services or a phone line.

## Decision

**Local engines, as an optional install.** `faster-whisper` listens, Piper speaks, and Silero, which `faster-whisper` already ships, detects speech. No account and no cost, and audio never leaves the machine or gets stored. The rest of the service and CI do not need them.

**The browser's microphone stands in for a phone line.** A real line needs a paid provider, and the channel only sees audio coming in and going out.

**The same agent.** Triage, guards and consent are the ones of every other channel, working on the transcript.

**The model for accuracy, not speed.** Measured on the bank, `base` heard "the bleeding won't stop" as "will stop", while `small` got every emergency right, at about twice the time. An emergency heard wrong costs more than two seconds.

**A vocabulary, checked for its side effect.** Telling Whisper which words to expect improved results a great deal, but those words include "sí, confirmo" and "no puedo respirar", so it might invent them from noise. The bank holds noise and unrelated speech to catch that, and no run has turned any into a yes or an emergency.

**A yes needs certainty, and a question heard to the end.** Agreeing changes an appointment, so a yes the engine is unsure of, or one said over a question still being read, confirms nothing. The question is read again in the code's own words. An emergency goes the other way: it counts however unsure the engine is, since mistaking something for one costs little.

**A holding phrase hides nothing.** The model takes seconds through the CLI, and silence on a call sounds broken. So the code says something fixed at once, and the report publishes the time to it and to the real answer apart.

**An interruption stops the audio at once**, and whatever answer was on its way is dropped. A question that was never heard cannot be agreed to. An action someone already agreed to still runs, because they asked for it.

**The socket checks its origin by hand.** No same-origin rule protects a socket, so any page could otherwise open a call from a visitor's browser. It also limits the size of each piece of audio, the length of a call and how many run at once.

## Consequences

- The accuracy figures come from synthetic audio, far cleaner than a real caller, so they are optimistic. They find what breaks rather than predict real calls.
- A yes passes the threshold of confidence with little margin, even on clean audio. With real voices it will often ask again, which costs time and is the safe way to fail.
- The real answer takes too long for a natural conversation, mostly because of the model through the CLI. A streaming API would help, which this project's budget rules out.
- Whisper on the CPU keeps a core busy per call, so calls are limited to two at once. The models weigh about 700 MB, and are downloaded on first use.
- Triage still matches text. An emergency described in words it does not know, or heard wrongly by the engine, reaches the model like anything else, whose guards still apply.
