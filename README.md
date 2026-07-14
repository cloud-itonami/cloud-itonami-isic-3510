# cloud-itonami-3510

Open Business Blueprint for **ISIC Rev.5 3510**: electric power
generation, transmission and distribution -- here scoped specifically
to grid TRANSMISSION AND DISTRIBUTION operations (the wires business:
substations, transmission lines, distribution feeders, customer
meters), distinct from generation.

This repository publishes a distribution-utility actor -- customer/
meter intake, identity verification, billing/service-dispute
screening, new-service provisioning and service disconnection -- as an
OSS business that any qualified, regulated community grid
transmission/distribution operator can fork, deploy, run, improve and
sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet, most closely
[`cloud-itonami-isic-6130`](https://github.com/cloud-itonami/cloud-itonami-isic-6130)
(this fleet's most recent REPO-LAYOUT precedent: satellite telecom) and
[`cloud-itonami-isic-3600`](https://github.com/cloud-itonami/cloud-itonami-isic-3600)
(this fleet's closest infrastructure/utility DOMAIN analog: community
water safety). Here it is **Grid Distribution Advisor ⊣ Grid
Transmission Governor** (`:grid-transmission-governor` in this repo's
own `blueprint.edn`, distinct from `cloud-itonami-isic-3512`'s own
`:grid-policy-governor`).

> **Why an actor layer at all?** An LLM is great at drafting a
> meter-intake summary, normalizing records, and checking whether a
> meter's own recorded meter number is even syntactically well-formed
> -- but it has **no notion of which jurisdiction's electricity-
> distribution/customer-protection requirements are official, no
> license to energize a real connection or disconnect a real
> customer's supply, and no way to know on its own whether a customer
> depends on that supply for life-support equipment or operates
> designated critical infrastructure**. Letting it provision service or
> disconnect a meter directly invites fabricated regulatory citations,
> a connection energized on a malformed meter number, an unresolved
> billing dispute being quietly ignored, and -- the gravest risk in
> this domain -- a life-support or critical-infrastructure customer
> being disconnected. This project seals the Grid Distribution Advisor
> into a single node and wraps it with an independent **Grid
> Transmission Governor**, a human **approval workflow**, and an
> immutable **audit ledger**.

## Scope note: transmission/distribution, not generation

`cloud-itonami-isic-3512` ("Community Renewable Energy Operations")
already covers community-scale GENERATION (a solar/wind cooperative).
This repository is deliberately scoped to the SEPARATE transmission/
distribution ("wires") side of the value chain -- the distribution
utility that moves power from any generator to any consumer, a
distinct regulated business in most jurisdictions (transmission/
distribution utilities are frequently separate legal entities from
generation companies, especially in deregulated markets).

### What this actor does and does not do

This actor covers customer/meter intake through identity verification,
billing/service-dispute screening, new-service provisioning and
service disconnection. It does **not**, by itself, hold any
electricity-distribution licence, franchise territory or
interconnection agreement required to operate a distribution grid in a
given jurisdiction, and it does not claim to. It also does **not**
model real SCADA/telemetry, a real substation/feeder dispatch system,
or emergency-outage/storm-restoration coordination -- no live grid-
state monitoring, no real breaker/switchgear command-and-control (see
`grid.facts`'s own docstring for the honest simplification this makes:
a starting catalog of electricity-distribution regulators, not a
survey of every jurisdiction's variant). Whoever deploys and operates
a live instance (a licensed distribution utility) supplies the real
distribution licence, the real grid infrastructure and any real
emergency-restoration integrations, and bears that jurisdiction's
liability -- the software supplies the governed, spec-cited, audited
execution scaffold so that operator does not have to build the
compliance layer from scratch for every new market.

### Actuation

**Disconnecting a real customer's electricity supply is never
autonomous, at any phase, by construction.** Two independent layers
enforce this (`grid.governor`'s `:actuation/disconnect-service`
high-stakes gate and `grid.phase`'s phase table, which never puts it
in any phase's `:auto` set) -- see `grid.phase`'s docstring and
`test/grid/phase_test.clj`'s `disconnect-service-never-auto-at-any-
phase`. Like `cloud-itonami-isic-6130`'s own `:actuation/suspend-
service`, this is a **NEGATIVE actuation**: it withholds an ongoing
necessity service rather than issuing a new record.

**A meter flagged `:protected-recipient?` (a life-support-equipment or
designated critical-infrastructure customer) can NEVER be
disconnected, by ANY actor run, at ANY confidence level, with or
without human approval.** This is a HARD, un-overridable governor
check (`grid.governor/protected-recipient-violations`) -- THIS FLEET'S
FIRST protected-recipient invariant; no prior sibling, including
`cloud-itonami-isic-3600`'s own water-safety actor, models an
analogous always-protected-class concept. See `docs/adr/0001-
architecture.md` Decision 4.

**New-service provisioning is high-stakes only conditionally**: a
clean, verified, well-formed connection whose own recorded capacity is
at or under `grid.registry/default-capacity-threshold-kw` MAY
auto-commit at phase 3; the SAME proposal for a connection whose
capacity exceeds the threshold always escalates for a human
distribution-capacity-impact review, regardless of phase. This is
THIS FLEET'S SECOND asymmetric dual-actuation shape (after
`cloud-itonami-isic-6391`'s `newswire.governor`), but on a genuinely
new dimension -- value-driven rather than op-kind-driven. See
`docs/adr/0001-architecture.md` Decision 3.

**Real SCADA/telemetry, breaker/switchgear command-and-control,
emergency-outage/storm-restoration coordination, and law-enforcement-
ordered disconnection are OUT OF SCOPE for this actor by construction**
-- there is no op, HARD check, or advisor dispatch branch for any of
them. See `docs/adr/0001-architecture.md`.

## The core contract

```
meter intake + jurisdiction facts (grid.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌──────────────────────────────┐
   │ Grid          │ ─────────────▶ │ Grid Transmission Governor:   │  (independent system)
   │ Distribution  │  + citations    │ spec-basis · evidence-        │
   │ Advisor       │                 │ incomplete · meter-number-    │
   │ (sealed)      │                 │ format-invalid (structural) · │
   └──────────────┘         commit ◀┤ protected-recipient (HARD,     ├─▶ hold
                                     │ un-overridable) · dispute-     │
                           record + ledger  unresolved (unconditional)│
                                     │ · already-provisioned/-        │
                                     │ disconnected                   │
                                     └────────────┬───────────────────┘
                                          escalate │ (ALWAYS for
                                                    │  :actuation/disconnect-service;
                                                    │  :actuation/provision-service
                                                    │  only when over the capacity
                                                    │  threshold or low-confidence)
                                                    ▼
                                                  human
```

**The Grid Distribution Advisor never provisions service or
disconnects a meter the Grid Transmission Governor would reject, and
never disconnects a protected recipient at all.** Hard violations
(fabricated jurisdiction requirements; unsupported evidence; a
malformed meter number; disconnecting a protected recipient; an
unresolved billing/service dispute; a double provisioning or
disconnection) force **hold** and *cannot* be approved past; a clean
disconnection proposal, and a clean over-threshold provisioning
proposal, still always route to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean auto-committing provisioning + a dual-actuation lifecycle + six HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (transmission-line
inspection, substation maintenance, feeder switching, meter
installation) operate under an actor that proposes actions, gated by
the independent **Grid Transmission Governor**. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions (any
dispatch that would violate a grid reliability standard, load-
shedding, emergency curtailment) require human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Grid Transmission Governor, service-provisioning + service-disconnection draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`3510`). Required capabilities are implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) -- missions, actions, safety-stops, telemetry proofs

`grid.*` cites this capability contract for the shape of a real robot
mission without requiring it directly, the SAME "related capability
contract but not required" posture every sibling actor establishes --
the actor is fully self-contained and runs offline with `MemStore`; a
production deployment wires the real capabilities in as its meter-
management and robot-dispatch backends.

## Layout

| File | Role |
|---|---|
| `src/grid/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + separate service-provisioning/service-disconnection history. Both actuation ops act directly on a pre-seeded meter, and the double-actuation guards check dedicated `:service-provisioned?`/`:service-disconnected?` booleans rather than a `:status` value |
| `src/grid/registry.cljc` | Service-provisioning + service-disconnection draft records, plus `meter-number-invalid-format?` (the FOURTH application of this fleet's format/syntactic-validity check family) and `capacity-over-threshold?` |
| `src/grid/facts.cljc` | Per-jurisdiction electricity-distribution catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/grid/gridadvisor.cljc` | **Grid Distribution Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/verification/dispute-screening/provisioning/disconnection proposals |
| `src/grid/governor.cljc` | **Grid Transmission Governor** -- 5 HARD checks (spec-basis · evidence-incomplete · meter-number-format-invalid, structural recompute · protected-recipient, THIS FLEET'S FIRST always-protected-class check · dispute-unresolved, unconditional evaluation) + already-provisioned/already-disconnected guards + 2 soft (capacity-over-threshold · confidence gate) |
| `src/grid/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted verify → supervised (disconnection always human; under-threshold provisioning and meter intake are the only auto-eligible ops) |
| `src/grid/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/grid/sim.cljc` | demo driver |
| `test/grid/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers customer/meter intake through identity verification,
billing/service-dispute screening, new-service provisioning and
service disconnection -- the core governed lifecycle this blueprint's
own `docs/business-model.md` names as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Meter intake + per-jurisdiction electricity-distribution checklisting, HARD-gated on an official spec-basis citation (`:meter/intake`/`:identity/verify`) | Real SCADA/telemetry, real substation/feeder dispatch integration (see `grid.facts`'s docstring) |
| Billing/service-dispute screening, evaluated unconditionally so the screening op itself can HARD-hold on its own finding (`:dispute/screen`) | Real robot dispatch for transmission-line inspection or meter installation |
| Service provisioning, HARD-gated on full evidence and meter-number structural validity, plus a double-provisioning guard; auto-eligible at phase 3 when under the capacity threshold (`:actuation/provision-service`) | Emergency-outage/storm-restoration coordination, and law-enforcement-ordered disconnection (deliberately outside LLM/actor control) |
| Service disconnection, HARD-gated on full evidence, protected-recipient status (un-overridable) and a double-disconnection guard, never auto at any phase (`:actuation/disconnect-service`) | |
| Immutable audit ledger for every intake/verification/screening/provisioning/disconnection decision | |

Extending coverage is additive: add the next gate (e.g. a seasonal-
disconnection-moratorium check) as its own governed op with its own
HARD checks and tests, following the SAME "an independent governor
re-verifies against the actor's own records before any real-world act"
pattern this repo's flagship op already establishes.

## Jurisdiction coverage (honest)

`grid.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `grid.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `grid.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to
make coverage look bigger.

## Maturity

`:implemented` -- `Grid Distribution Advisor` + `Grid Transmission
Governor` run as real, tested code (see `Run` above), modeled on
`cloud-itonami-isic-6130`'s repo layout and `cloud-itonami-isic-3600`'s
infrastructure/utility domain shape. See `docs/adr/0001-
architecture.md` for the history and design.

## License

AGPL-3.0-or-later.
