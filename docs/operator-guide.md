# Operator Guide

## First Deployment
1. Register operator, substations/feeders, interconnection requests,
   staff and robots.
2. Import existing dispatch and settlement history.
3. Run read-only interconnection-capacity-scope and grid-inspection
   robot mission dry-runs.
4. Configure safety-class allowed sets and human sign-off paths.
5. Publish a dry-run settlement record and audit export.

## Minimum Production Controls
- interconnection-capacity-scope validation before any dispatch
- governor gate on every robot action before dispatch
- human sign-off for :high/:safety-critical actions (reliability-
  standard-violating dispatch, load-shedding, emergency curtailment)
- evidence-backed settlement records
- audit export for every dispatch, sign-off and settlement
- backup manual grid-operations process
- customer/meter identity verification on file before any service
  provisioning or disconnection proposal is even evaluated
- service disconnection always requires human sign-off, and is
  permanently refused (no override) for any meter registered as a
  life-support or critical-infrastructure protected recipient
- new-service provisioning above the connection-capacity threshold
  requires a human distribution-capacity-impact review before
  energizing

## Certification
Certified operators must prove robot-safety integrity, interconnection-
capacity discipline, evidence-backed settlement records and human
review for reliability-affecting actions.
