# The same bank with several models

Each scenario ran once per model, so a difference of a scenario or two may be chance. The patient simulator is the same in every column. Cost is what the API would charge, simulator included: the plan in use has no cost per call.

| Model | Recorded | Passed | Agent calls | Tokens read | Tokens written | Est. cost | Cost per scenario | Call p50 | Call p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `haiku` | 50/50 | 49 (98%) | 148 | 984412 | 102700 | $0.1392 | $0.0028 | 4.4 s | 9.2 s |
| `opus` | 50/50 | 46 (92%) | 142 | 967761 | 62096 | $2.9980 | $0.0600 | 3.3 s | 10.1 s |
| `sonnet` | 50/50 | 48 (96%) | 138 | 885421 | 63334 | $1.4585 | $0.0292 | 5.1 s | 9.2 s |

## Scenarios on which the models differ

| Scenario | haiku | opus | sonnet |
| --- | --- | --- | --- |
| ambiguous-wants-an-appointment | pass | **fail** | pass |
| asks-the-policy-without-cancelling | pass | **fail** | pass |
| book-filling-with-toothache | pass | pass | **fail** |
| move-to-another-day | pass | **fail** | pass |
