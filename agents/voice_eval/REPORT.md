# How well the voice channel hears, and how long it takes

Measured on this project's own machine, on the CPU. The audio comes from Piper, the same engine that speaks, with and without added noise. That is much cleaner than a real person on a real phone, **so these figures are optimistic**: they find what breaks, and do not predict how it would go with real callers.

Whisper model: `small`.

| What must hold | Clean audio | With noise |
| --- | ---: | ---: |
| Emergencies reach triage | 10 of 10 | 10 of 10 |
| A yes is taken as a yes | 3 of 5 | 5 of 5 |
| Anything else is not taken as a yes | 5 of 5 | 5 of 5 |
| Days and treatments are heard | 10 of 10 | 9 of 10 |
| Noise and unrelated speech become neither a yes nor an emergency | 14 of 14 | 14 of 14 |

## What was not heard right

| Phrase | Audio | Said | Heard | Confidence |
| --- | --- | --- | --- | ---: |
| yes-si | clean | Sí. | "Sí." | 0.60 |
| yes-vale | clean | Vale. | "Vale." | 0.59 |
| day-jueves | noisy | Cámbiamela al jueves por la tarde. | "Cámbiame la aljuves por la tarde." | 0.50 |

## How long it takes

- Transcribing a phrase: median 2.04 s, p95 2.17 s.
- Speaking: 0.040 s of work per second of audio.

From the end of speech, on 10 turns of calls with `haiku`, through the Claude CLI. A turn with no model, such as an emergency, only takes the transcription.

| Until | Median | p95 |
| --- | ---: | ---: |
| The transcript | 2.07 s | 2.13 s |
| The first audio, a fixed holding phrase | 2.13 s | 2.17 s |
| The real answer | 7.36 s | 13.89 s |

The wait for the real answer is mostly the model through the CLI. It is too long for a natural conversation, and nothing here hides that.

## Whole calls with the real model

**10 of 10 went as they should.**

| Call | Result | |
| --- | --- | --- |
| emergency | pass |  |
| emergency-en | pass |  |
| opening-hours | pass |  |
| price | pass |  |
| medical-question | pass |  |
| injection | pass |  |
| no-appointments | pass |  |
| opening-hours-en | pass |  |
| says-yes-to-nothing | pass |  |
| wants-a-check-up | pass |  |
