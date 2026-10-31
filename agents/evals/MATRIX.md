# The same bank with several models

Each scenario ran once per model, so a difference of a scenario or two may be chance. The patient simulator is the same in every column. Cost is what the API would charge, simulator included: the plan in use has no cost per call.

| Model | Recorded | Passed | Agent calls | Tokens read | Tokens written | Est. cost | Cost per scenario | Call p50 | Call p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `sonnet` | 50/50 | 48 (96%) | 138 | 885420 | 63267 | $1.4519 | $0.0290 | 5.1 s | 9.2 s |

## Scenarios on which the models differ

None, among the scenarios recorded.
