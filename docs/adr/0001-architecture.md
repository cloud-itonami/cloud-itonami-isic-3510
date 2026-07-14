# ADR-0001: Grid Distribution Advisor ⊣ Grid Transmission Governor architecture

## Status

Accepted. `cloud-itonami-isic-3510` promoted from a published
`:blueprint`-only repo (business-model.md + operator-guide.md +
`blueprint.edn` + governance docs, no `src`/`test`) to `:implemented`
in the `kotoba-lang/industry` registry. This is ADR-2607121000's own
explicit Top-10 value-ranking item #6 ("3510/3512 電力(lattice の電力
需要と垂直統合)"), the first ISIC Wave 1 class promoted.

## Context

`cloud-itonami-isic-3510` publishes an OSS business blueprint for
community grid transmission/distribution operations: customer/meter
intake, per-jurisdiction identity/regulatory verification, billing/
service-dispute screening, new-service provisioning and service
disconnection, run by a qualified, regulated distribution utility.
Unlike `cloud-itonami-isic-6130`/`-6391` (this fleet's two most
recently completed actors, both direct `:spec`→`:implemented`
promotions with no prior blueprint stage), `"3510"` already had a
published `:blueprint`-only repo (`blueprint.edn`, `docs/business-
model.md`, `docs/operator-guide.md`, community files) from an earlier
build (ADR-2607101800) -- this build adds the governed-actor
implementation (`src`/`test`/`deps.edn`/this ADR) on top of that
existing scaffold, the SAME "blueprint promoted to implemented in a
later build" precedent `cloud-itonami-isic-6120` itself used.

## Decision

### Decision 1: mirror `cloud-itonami-isic-6130`'s module shape (repo layout), `cloud-itonami-isic-3600`'s domain shape (closest utility analog)

This repo's module names (`grid.facts`/`registry`/`store`/
`gridadvisor`/`governor`/`phase`/`operation`/`sim`), its `Store`
protocol shape (`MemStore` ‖ `DatomicStore` via `langchain.db`, proven
in `test/grid/store_contract_test.clj`) and its `deps.edn`/community-
file/`docs/adr` conventions directly mirror `satcom.*`
(`cloud-itonami-isic-6130`, this fleet's most recent REPO-LAYOUT
precedent at the time this build started). The primary entity is a
`meter` (a customer's electricity connection point, tied to a
jurisdiction's electricity-distribution regulatory scope), analogous
to `satcom.store`'s `terminal` and `water.store`'s `site`. The domain
itself -- a regulated infrastructure utility with a real disconnection/
suspension actuation carrying public-welfare stakes -- most closely
mirrors `water.*` (`cloud-itonami-isic-3600`), this fleet's OTHER
infrastructure/utility vertical and the closest DOMAIN analog checked
per this build's own task framing.

### Decision 2: `protected-recipient-violations` -- THIS FLEET'S FIRST always-protected-class HARD check

Checked explicitly against `water.governor` (`cloud-itonami-isic-3600`,
the task's own suggested closest analog for a "protected utility
recipient" concept) and `satcom.governor` (`cloud-itonami-isic-6130`):
**neither models an analogous concept** (confirmed by a full read of
both governors' HARD-check lists and a grep across every `orgs/cloud-
itonami/*` checkout for "life-support"/"critical-infrastructure"/
"protected-recipient", zero hits before this build). `water.governor`'s
own high-stakes negative actuation (`:actuation/suppress-alert`) has no
analogous "this specific recipient must never be the target of the
actuation" guard -- its HARD checks are all about the PROPOSAL's
evidentiary/spec basis, not a permanent property of a specific
recipient that forecloses the actuation outright regardless of
evidence quality. `grid.governor/protected-recipient-violations` is
therefore a genuinely NEW check kind for this fleet: it independently
recomputes the meter's own permanent `:protected-recipient?` field
(true for a life-support-equipment or designated critical-
infrastructure customer -- many real jurisdictions require a
utility to maintain an equivalent "medical baseline"/"critical care"
customer list governing disconnection, e.g. the UK's Priority Services
Register cited in this repo's own `grid.facts` GBR entry) for
`:actuation/disconnect-service`, and treats a hit as an ABSOLUTE,
un-overridable HOLD -- no confidence level and no human approval can
clear it, unlike every other HARD check in this fleet's history, all
of which a sufficiently well-evidenced re-submission could eventually
clear (a fabricated spec-basis citation could be replaced with a real
one; incomplete evidence could be completed). A protected-recipient
disconnection is not a "try again with better evidence" case -- this
actor never performs it, full stop.

### Decision 3: `capacity-over-threshold-violations` -- THIS FLEET'S SECOND asymmetric dual-actuation shape, on a NEW (value-driven) dimension

`cloud-itonami-isic-6391`'s `newswire.governor` introduced this
fleet's first asymmetric dual-actuation shape: `:actuation/distribute`
is high-stakes only when its own HARD/ESCALATE checks find something,
while `:actuation/issue-correction` is unconditionally high-stakes --
an asymmetry driven by WHICH op is proposed. This actor's own
asymmetry is driven by a different axis entirely: `:actuation/
provision-service` is high-stakes only when the SAME op's own
proposal targets a meter whose recorded `:capacity-kw` exceeds
`grid.registry/default-capacity-threshold-kw` -- a VALUE carried by
the request, not the op's kind. A clean, verified, well-formed
RESIDENTIAL/small-commercial connection may reach phase-3 auto-commit;
the identical clean proposal for a large industrial connection always
escalates for a human distribution-capacity-impact review, mirroring
how real distribution operators commonly require a load-flow/
protection study above a comparable small-commercial threshold before
energizing a large connection. `:actuation/disconnect-service` remains
unconditionally high-stakes (this fleet's now-fifth negative
actuation, after `cloud-itonami-isic-3600`'s alert suppression,
`6190`'s billing-record suppression, `6120`'s and `6130`'s own service
suspension) -- withholding electricity, a necessity service, is never
an autonomous act regardless of any threshold.

### Decision 4: `meter-number-invalid-format?` -- the FOURTH application of this fleet's format/syntactic-validity check family

`grid.registry/meter-number-invalid-format?` independently recomputes
whether a meter's own recorded meter number is a syntactically valid
IEC-62056-21-style numeric nameplate serial (8-12 digits, no letters).
This reuses the SAME check shape `telecom.registry/e164-invalid-
format?` (`6190`) established as this fleet's first format/syntactic-
validity check family, `wirelesstelecom.registry/msisdn-invalid-
format?` (`6120`) reused a second time and `satcom.registry/satellite-
number-invalid-format?` (`6130`) reused a third time -- a genuinely
different real-world identifier (a utility meter's own nameplate
serial), the same "recompute a permanent ground-truth field" check
discipline. It gates only `:actuation/provision-service`, the same
restricted-scope placement every prior format check in this fleet
uses.

### Decision 5: `dispute-unresolved-violations` -- reuses the established unconditional-evaluation dispute-screening discipline

An unresolved billing/service dispute on the meter -- reported by
`:dispute/screen` itself or already on file for the meter -- is a
HARD, un-overridable hold, evaluated UNCONDITIONALLY (not scoped to a
specific op) so the screening op itself can HARD-hold on its own
finding. Mirrors `satcom.governor/coordination-dispute-unresolved-
violations` (`6130`) and `water.governor/threshold-breach-unresolved-
violations` (`3600`), the established discipline this fleet's
unconditional-evaluation dispute/screening checks all share.

### Decision 6: no `:effect :propose` marker, matching `satcom.*` not `newswire.*`

Unlike `cloud-itonami-isic-6391`'s `newswire.advisor` (whose proposals
carry BOTH a literal `:effect :propose` marker AND a separate `:action`
field, independently re-checked by `newswire.governor/no-actuation-
violations`), this repo's `grid.gridadvisor` proposals follow `satcom.
satcomadvisor`'s shape: `:effect` IS the specific SSoT-mutation
instruction (e.g. `:meter/mark-provisioned`) directly. The "advisor
never itself writes the SSoT" invariant is already structurally
enforced -- `grid.operation`'s `:commit` node is the ONLY node that
calls `store/commit-record!`, and it only runs after the governor and
phase gate both clear the proposal -- so a separate propose-marker
re-check would be redundant here, the same reasoning `satcom.governor`
itself relies on (and unlike `newswire`'s own domain, where the
task's explicit "the actor never itself pushes content to the wire"
framing motivated stating the invariant literally). This repo's own
task framing similarly states this actor should never directly
mutate an account, but since REPO-LAYOUT precedent for this build is
`satcom`/`water` (Decision 1), not `newswire`, this build follows
their established shape rather than introducing the marker.

### Decision 7: dedicated `:service-provisioned?`/`:service-disconnected?` booleans

The SAME discipline every prior sibling governor's guards establish,
informed by `cloud-itonami-isic-6492`'s real status-lifecycle bug
(ADR-2607071320): `already-provisioned-violations`/`already-
disconnected-violations` check dedicated booleans on the meter record,
never a single mutable `:status` field.

### Decision 8: Store protocol, `MemStore` + `DatomicStore` parity

`grid.store/Store` is implemented by both `MemStore` (atom-backed,
default for dev/tests/demo) and `DatomicStore` (`langchain.db`-backed),
proven to satisfy the same contract in `test/grid/
store_contract_test.clj` -- the same seam every sibling actor uses so
swapping the SSoT backend is a configuration change, not a rewrite.

### Decision 9: Phase 0→3 rollout, asymmetric at phase 3

Phase 3's `:auto` set has TWO members: `:meter/intake` (no capital
risk) and `:actuation/provision-service` (auto-eligible IN PRINCIPLE,
but still gated per-request by the governor's independent capacity-
threshold recompute -- see Decision 3). `:identity/verify` and
`:dispute/screen` are never auto-eligible at any phase (matching every
sibling's screening/verification-op posture), and `:actuation/
disconnect-service` is permanently excluded from every phase's `:auto`
set -- a structural fact, not a rollout milestone, enforced by BOTH
`grid.phase` and `grid.governor`'s `high-stakes` set independently.

### Decision 10: real SCADA/telemetry, breaker/switchgear command-and-control, emergency-outage/storm-restoration coordination and law-enforcement-ordered disconnection are OUT OF SCOPE by construction

This repo's own already-published `docs/business-model.md` Trust
Controls do not name real-time grid telemetry, breaker/switchgear
control, or emergency/storm-restoration coordination as in scope,
mirroring `6130`'s own explicit "lawful-intercept and ground-station
command-and-control remain outside LLM control" precedent. There is no
op, HARD check, or `grid.gridadvisor/infer` dispatch branch for
commanding real switchgear, disclosing customer location, or a law-
enforcement-ordered disconnection distinct from the ordinary
`:actuation/disconnect-service` (non-payment/unresolved-dispute) op. A
production deployment wires these regulated paths through its own
dedicated SCADA/emergency infrastructure and legal process, entirely
outside this actor's LLM advisor and governor.

## Alternatives considered

- **Modeling the protected-recipient concept as a per-jurisdiction
  spec-basis field in `grid.facts` instead of a per-meter ground-truth
  field.** Rejected: whether a SPECIFIC customer depends on life-
  support equipment or operates critical infrastructure is a property
  of that customer/meter, not of the jurisdiction as a whole -- a
  jurisdiction-level field could not express "this specific meter is
  protected, that one is not." The per-meter `:protected-recipient?`
  field, checked independently by the governor exactly like every
  other ground-truth ("ground-truth field on the meter") check in this
  fleet, is the honest shape.
- **Making `protected-recipient-violations` a SOFT escalation (human
  may override with sufficient justification), matching `newswire.
  governor/legally-sensitive-violations`'s SOFT treatment of a
  comparable "serious but reviewable" risk.** Rejected: the task's own
  explicit framing states disconnecting a life-support/critical-
  infrastructure meter "must always hold, never overridable regardless
  of confidence" -- unlike a legal-sensitivity risk (where an editor's
  informed judgment call is exactly the appropriate mitigation), there
  is no informed-judgment case in which this actor should perform a
  real disconnection against a protected recipient; the failure mode
  (an at-risk person losing life-support power, or a hospital/
  emergency-services facility losing power) is categorically different
  in kind from a reviewable legal risk.
- **Making `:actuation/provision-service` permanently excluded from
  every phase's `:auto` set, matching `satcom`'s/`water`'s own
  symmetric dual-actuation shape.** Rejected: the task's own domain
  framing explicitly anticipates "any new-service provisioning above
  some capacity threshold" escalating (implying under-threshold
  provisioning need not), and a real distribution utility's ordinary
  operating cadence does not put a human in the loop on every single
  small residential connection -- collapsing this distinction into
  symmetric always-human treatment would misrepresent that cadence,
  the SAME reasoning `newswire.governor`'s own ADR used for
  `:actuation/distribute`.
- **A single actuation (provisioning only), treating disconnection as
  a lower-stakes administrative note.** Rejected: this repo's own
  `docs/business-model.md` Trust Controls already state "safety-
  critical actions... require human sign-off," and disconnection of a
  necessity service carries the gravest stakes of any actuation in
  this domain -- the same posture every prior negative-actuation
  sibling's ADR used to justify treating it as high-stakes.

## Consequences

- Confirms the negative-actuation pattern generalizes a fifth time
  (water-safety alerting, wired-telecom billing, terrestrial-mobile
  service continuity, satellite service continuity, electricity
  service continuity), not a one-off quirk of any single domain.
- Confirms `6391`'s asymmetric-dual-actuation pattern generalizes to a
  SECOND, value-driven (not op-kind-driven) dimension -- a template
  other domains with a "clean-and-small autonomous, clean-but-large
  always-human" shape may reuse.
- Introduces this fleet's first protected-recipient / always-
  un-overridable-HARD-hold check -- a template other domains with an
  analogous vulnerable-recipient concept (e.g. a future healthcare- or
  eldercare-adjacent actor) may reuse.
- `kotoba-lang/industry`'s `:blueprint` tier count decreases by one and
  `:implemented` increases by one; ISIC Wave 1 (ADR-2607121000,
  superproject) advances by its first class, closing this build's own
  explicit Top-10 value-ranking item #6.
