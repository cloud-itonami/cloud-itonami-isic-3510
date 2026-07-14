# Business Model: Community Grid Transmission and Distribution Operations

## Classification
- Repository: `cloud-itonami-3510`
- ISIC Rev.5: `3510` — electric power generation, transmission and
  distribution (this repository: transmission/distribution scope)
- Social impact: grid reliability, energy access, interconnection
  fairness

## Customer
- independent transmission/distribution utilities needing an
  auditable interconnection and dispatch platform
- generators (of any kind, including `cloud-itonami-isic-3512`'s own
  community renewable cooperatives) needing verifiable
  interconnection/wheeling records
- regulators needing verifiable grid-reliability and settlement
  records
- programs that cannot accept closed, unauditable grid-management
  platforms

## Offer
- interconnection and wheeling-service request management
- robotics-assisted transmission-line/substation inspection and
  maintenance
- dispatch and telemetry-observation records
- settlement and disclosure records
- customer/meter intake, identity verification and new-service
  provisioning (governed; R0 actor scope, see `src/grid/*` and
  `docs/adr/0001-architecture.md`)
- billing/service-dispute screening and, when unavoidable, service
  disconnection -- always human-approved, and permanently refused for
  any meter registered as a life-support or critical-infrastructure
  protected recipient
- role-based access and immutable audit ledger

## R0 governed scope (honest)
The governed actor implementation (`src/grid/*`) covers the
customer/meter-level slice of this Offer -- meter intake, identity
verification, billing/service-dispute screening, new-service
provisioning and service disconnection -- not yet the interconnection/
wheeling-request or settlement lifecycle between generators and the
grid operator. See `docs/adr/0001-architecture.md` and this repo's own
README `Business-process coverage` table for exactly which ops are
governed today; extending coverage to interconnection/wheeling/
settlement follows the SAME "independent governor re-verifies before
any real-world act" pattern as its own additive next step.

## Revenue
- self-host setup fee
- managed hosting subscription per substation/feeder
- support retainer with SLA
- inspection/maintenance robot integration and maintenance

## Trust Controls
- a robot action the governor refuses is never dispatched
- safety-critical actions (any dispatch that would violate a grid
  reliability standard, load-shedding, emergency curtailment) require
  human sign-off
- an interconnection cannot be approved outside its verified capacity
  scope
- settlement records require verified telemetry evidence
- service disconnection is never autonomous, at any phase, and a
  meter registered as a life-support or critical-infrastructure
  protected recipient can never be disconnected regardless of
  confidence or approval (`grid.governor/protected-recipient-
  violations`, HARD and un-overridable)
- new-service provisioning above the connection-capacity threshold
  always requires a human distribution-capacity-impact review
- sensitive interconnection and customer data stays outside Git
