# Contributing

`cloud-itonami-3510` accepts contributions to the OSS blueprint, capability
bindings, policy tests, documentation and operator model.

## Development
The capability layer lives in `kotoba-lang/robotics`. This repo holds the
business blueprint and operator contracts.

```bash
clojure -X:test
clojure -M:lint
```

## Rules
- Do not commit real interconnection, customer or telemetry data.
- Keep robot dispatch, interconnection approvals and settlement
  records behind the Grid Transmission Governor.
- Treat dispatch/settlement workflows as high-risk: add tests for
  robot-safety gating, capacity scope, evidence, disclosure and audit
  logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
