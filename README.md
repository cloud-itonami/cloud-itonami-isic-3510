# cloud-itonami-3510

Open Business Blueprint for **ISIC Rev.5 3510**: electric power
generation, transmission and distribution -- here scoped specifically
to grid TRANSMISSION AND DISTRIBUTION operations (the wires business:
substations, transmission lines, distribution feeders), distinct from
generation.

This repository designs a forkable OSS business for community grid
transmission/distribution operations: interconnection and
wheeling-service management, robotics-assisted grid inspection and
maintenance, and dispatch/settlement records — run by a qualified
operator so a grid operator keeps its own interconnection and dispatch
history instead of renting a closed grid-management platform.

## Scope note: transmission/distribution, not generation

`cloud-itonami-isic-3512` ("Community Renewable Energy Operations")
already covers community-scale GENERATION (a solar/wind cooperative).
This repository is deliberately scoped to the SEPARATE transmission/
distribution ("wires") side of the value chain -- the grid operator
that moves power from any generator to any consumer, a distinct
regulated business in most jurisdictions (transmission/distribution
utilities are frequently separate legal entities from generation
companies, especially in deregulated markets).

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (transmission-line
inspection, substation maintenance, feeder switching) operate under an
actor that proposes actions and an independent **Grid Transmission
Governor** that gates them. The governor never dispatches a grid
operation itself; `:high`/`:safety-critical` actions (any dispatch that
would violate a grid reliability standard, any load-shedding or
emergency curtailment) require human sign-off.

## Core Contract

```text
intake + identity + interconnection request + telemetry observation
        |
        v
Grid Operations Advisor -> Grid Transmission Governor -> interconnection record, dispatch, settlement record, or human approval
        |
        v
robot actions (gated) + dispatch record + settlement record + audit ledger
```

No automated advice can dispatch a grid operation the governor refuses,
approve an interconnection outside its verified capacity scope, or
publish a settlement record without governor approval and audit
evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `3510`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
