# Governance

`cloud-itonami-3510` is an OSS open-business blueprint for community
grid transmission and distribution operations, robotics-premised.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a robot action the governor refuses is never dispatched to hardware.
- the Grid Transmission Governor remains independent of the advisor.
- hard policy violations (out-of-capacity interconnection, reliability-standard-violating dispatch, evidenceless settlement record) cannot be overridden by human approval.
- every dispatch, sign-off, interconnection and settlement path is auditable.
- sensitive interconnection and customer data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model, storage contract, public business model, operator certification or license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a separate trust mark and should require security, robot-safety, audit and data-flow review.

Certified operators can lose certification for:
- bypassing robot-safety or capacity-scope checks
- mishandling interconnection or customer data
- misrepresenting certification status
- failing to respond to grid-reliability incidents
