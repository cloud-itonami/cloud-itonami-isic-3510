(ns grid.registry
  "Pure-function service-provisioning + service-disconnection record
  construction -- an append-only distribution-utility book-of-record
  draft.

  Like every sibling actor's registry, there is no single international
  check-digit standard for a service-provisioning or service-
  disconnection reference number -- every utility/jurisdiction assigns
  its own reference format. This namespace does NOT invent one; it
  builds a jurisdiction-scoped sequence number and validates the
  record's required fields, the same honest, non-fabricating discipline
  `grid.facts` uses.

  `meter-number-invalid-format?` is the FOURTH application of this
  fleet's format/syntactic-validity check family, first established by
  `telecom.registry/e164-invalid-format?` (`cloud-itonami-isic-6190`),
  reused by `wirelesstelecom.registry/msisdn-invalid-format?`
  (`cloud-itonami-isic-6120`) and `satcom.registry/satellite-number-
  invalid-format?` (`cloud-itonami-isic-6130`). A genuine fourth
  application, not a new family: a utility meter's own nameplate serial
  number (per IEC 62056-21, the widely-used companion standard to the
  DLMS/COSEM electricity-metering data-exchange protocol) is
  conventionally an 8-12 digit numeric string, structurally distinct
  from an E.164-style telecom identifier but the SAME 'independently
  recompute whether a permanent ground-truth field is well-formed'
  check shape. It gates only `:actuation/provision-service` (the point
  where a malformed meter number would otherwise get energized for real
  use), the same restricted-scope placement every prior format check in
  this fleet uses.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real meter, substation or SCADA system. It builds the
  RECORD a utility would keep, not the act of provisioning service or
  disconnecting a meter itself (that is `grid.operation`'s `:actuation/
  provision-service`/`:actuation/disconnect-service`, always human-
  gated for disconnection, and gated on a capacity threshold for
  provisioning -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  utility's own act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn meter-number-invalid-format?
  "Is `meter`'s own recorded `:meter-number` NOT a syntactically valid
  IEC-62056-21-style numeric nameplate serial (8-12 digits, no
  letters/symbols)? A pure ground-truth check against the meter's own
  permanent field -- no upstream comparison needed. The FOURTH
  application of this fleet's format/syntactic-validity check family
  (see ns docstring)."
  [{:keys [meter-number]}]
  (or (nil? meter-number)
      (not (re-matches #"\d{8,12}" meter-number))))

(def default-capacity-threshold-kw
  "The connection capacity (in kW) above which a new-service
  provisioning is treated as high-stakes even when otherwise
  governor-clean -- an actor-level policy default (like `grid.governor/
  confidence-floor`), not a per-jurisdiction spec-basis citation. Real
  distribution operators commonly require a distribution capacity-
  impact/protection study above a comparable small-commercial threshold
  before energizing a connection; this repo does not claim a specific
  jurisdiction's exact figure, only a plausible, documented default a
  deployment should tune to its own regulator's real threshold."
  50)

(defn capacity-over-threshold?
  "Is `meter`'s own recorded `:capacity-kw` strictly greater than
  `threshold-kw` (`default-capacity-threshold-kw` when omitted)? A pure
  ground-truth check -- no upstream comparison needed."
  ([meter] (capacity-over-threshold? meter default-capacity-threshold-kw))
  ([{:keys [capacity-kw]} threshold-kw]
   (boolean (and (number? capacity-kw) (> capacity-kw threshold-kw)))))

(defn register-service-provisioning
  "Validate + construct the SERVICE-PROVISIONING registration DRAFT --
  the utility's own act of energizing a real connection and a real
  meter for a customer. Pure function -- does not touch any real meter,
  substation or SCADA system; it builds the RECORD a utility would
  keep. `grid.governor` independently re-verifies the meter's own
  number-format validity, capacity-threshold standing and identity-
  verification sufficiency, and blocks a double-provisioning for the
  same meter, before this is ever allowed to commit."
  [meter-id jurisdiction sequence]
  (when-not (and meter-id (not= meter-id ""))
    (throw (ex-info "service-provisioning: meter_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "service-provisioning: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "service-provisioning: sequence must be >= 0" {})))
  (let [provisioning-number (str (str/upper-case jurisdiction) "-PRV-" (zero-pad sequence 6))
        record {"record_id" provisioning-number
                "kind" "service-provisioning-draft"
                "meter_id" meter-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "provisioning_number" provisioning-number
     "certificate" (unsigned-certificate "ServiceProvisioning" provisioning-number provisioning-number)}))

(defn register-service-disconnection
  "Validate + construct the SERVICE-DISCONNECTION registration DRAFT --
  the utility's own act of disconnecting a real customer's meter. Pure
  function -- does not touch any real meter, substation or SCADA
  system; it builds the RECORD a utility would keep. `grid.governor`
  independently re-verifies the meter's own protected-recipient
  (life-support/critical-infrastructure) standing and unresolved-
  dispute status, and blocks a double-disconnection for the same
  meter, before this is ever allowed to commit. Like `satcom.registry/
  register-service-suspension` (`cloud-itonami-isic-6130`),
  `wirelesstelecom.registry/register-service-suspension` (`cloud-
  itonami-isic-6120`) and `telecom.registry/register-billing-
  suppression` (`cloud-itonami-isic-6190`), this actuation is a
  NEGATIVE act (withholding an ongoing supply of electricity, a
  necessity service), not a positive one -- see README `Actuation` and
  this actor's own ADR-0001 for the honest framing this makes."
  [meter-id jurisdiction sequence]
  (when-not (and meter-id (not= meter-id ""))
    (throw (ex-info "service-disconnection: meter_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "service-disconnection: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "service-disconnection: sequence must be >= 0" {})))
  (let [disconnection-number (str (str/upper-case jurisdiction) "-DSC-" (zero-pad sequence 6))
        record {"record_id" disconnection-number
                "kind" "service-disconnection-draft"
                "meter_id" meter-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "disconnection_number" disconnection-number
     "certificate" (unsigned-certificate "ServiceDisconnection" disconnection-number disconnection-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))

;; ----------------------------- outage-event / restoration (additive) -----------------------------
;;
;; Pure-function outage-event-logging + restoration-reporting record
;; construction -- SAME append-only draft-record discipline as
;; `register-service-provisioning`/`register-service-disconnection`
;; above, applied to a DIFFERENT entity pair: (feeder, outage-event)
;; rather than (meter, service). `grid.governor` independently
;; re-verifies (via `grid.store/feeder-has-open-outage?`/`grid.store/
;; outage-open?`) that a feeder does not already have an outage open
;; before `:actuation/log-outage-event` commits, and that a
;; `:actuation/report-restoration` proposal's outage-id actually
;; resolves to a currently-open outage, before this is ever allowed to
;; commit -- the SAME double-actuation-guard shape
;; `meter-already-provisioned?`/`meter-already-disconnected?` already
;; establish, never a `:status` value.

(defn register-outage-event
  "Validate + construct the OUTAGE-EVENT-LOGGING draft -- the record a
  utility's own outage-management system would keep when an outage on
  a feeder is first detected/reported. Pure function -- does not touch
  any real feeder, breaker or SCADA system; it builds the RECORD a
  utility would keep."
  [feeder-id outage-id jurisdiction cause-category sequence]
  (when-not (and feeder-id (not= feeder-id ""))
    (throw (ex-info "outage-event: feeder_id required" {})))
  (when-not (and outage-id (not= outage-id ""))
    (throw (ex-info "outage-event: outage_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "outage-event: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "outage-event: sequence must be >= 0" {})))
  (let [outage-number (str (str/upper-case jurisdiction) "-OUT-" (zero-pad sequence 6))
        record {"record_id" outage-number
                "kind" "outage-event-draft"
                "feeder_id" feeder-id
                "outage_id" outage-id
                "cause_category" (str cause-category)
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "outage_number" outage-number
     "certificate" (unsigned-certificate "OutageEvent" outage-number outage-number)}))

(defn register-outage-restoration
  "Validate + construct the OUTAGE-RESTORATION-REPORTING draft -- the
  record a utility's own outage-management system would keep when a
  previously-logged outage is reported restored. Pure function -- does
  not touch any real feeder, breaker or SCADA system; it builds the
  RECORD a utility would keep. `grid.governor` independently
  re-verifies that `outage-id` actually resolves to a currently-open
  outage before this is ever allowed to commit -- see ns docstring."
  [outage-id jurisdiction sequence]
  (when-not (and outage-id (not= outage-id ""))
    (throw (ex-info "outage-restoration: outage_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "outage-restoration: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "outage-restoration: sequence must be >= 0" {})))
  (let [restoration-number (str (str/upper-case jurisdiction) "-RST-" (zero-pad sequence 6))
        record {"record_id" restoration-number
                "kind" "outage-restoration-draft"
                "outage_id" outage-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "restoration_number" restoration-number
     "certificate" (unsigned-certificate "OutageRestoration" restoration-number restoration-number)}))
