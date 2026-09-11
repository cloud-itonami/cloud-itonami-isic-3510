(ns grid.governor
  "Grid Transmission Governor -- the independent compliance layer that
  earns the Grid Distribution Advisor the right to commit (named
  `:grid-transmission-governor` in this repo's own `blueprint.edn`,
  distinct from `cloud-itonami-isic-3512`'s own `:grid-policy-
  governor`). The LLM has no notion of which jurisdiction's
  electricity-distribution/customer-protection law is official, whether
  a meter's own recorded meter number is even syntactically valid,
  whether a meter's own connection capacity actually clears the
  interconnection-review threshold, whether a meter is a protected
  (life-support/critical-infrastructure) recipient that must never be
  disconnected, whether an unresolved billing/service dispute against
  the meter has actually stayed unresolved, or when an act stops being
  a draft and becomes a real-world service provisioning or
  disconnection, so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD -- the electric-distribution-utility
  analog of `cloud-itonami-isic-6130`'s `satcom.governor` (this fleet's
  most recent REPO-LAYOUT precedent) and `cloud-itonami-isic-3600`'s
  `water.governor` (this fleet's closest infrastructure/utility DOMAIN
  analog).

  Six checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them (you don't get to approve your way past a
  fabricated jurisdiction spec-basis, incomplete evidence, a malformed
  meter number, disconnecting a protected recipient, an unresolved
  billing/service dispute, or a double provisioning/disconnection). The
  confidence/high-stakes gate is SOFT: it asks a human to look (low
  confidence / high-stakes), and the human may approve -- but see
  `grid.phase`: for `:actuation/disconnect-service` (a real,
  necessity-service-withholding act) NO phase ever allows auto-commit
  either -- two independent layers agree that disconnection is always a
  human call.

    1. Spec-basis                  -- did the identity proposal cite an
                                       OFFICIAL electricity-distribution
                                       source (`grid.facts`), or invent
                                       one?
    2. Evidence incomplete         -- for `:actuation/provision-
                                       service`/`:actuation/disconnect-
                                       service`, has the meter actually
                                       been identity-verified with a
                                       full evidence checklist on file?
    3. Meter-number format
       invalid                      -- for `:actuation/provision-
                                       service`, INDEPENDENTLY recompute
                                       whether the meter's own recorded
                                       meter number is syntactically
                                       valid (`grid.registry/meter-
                                       number-invalid-format?`) -- needs
                                       no proposal inspection or
                                       stored-verdict lookup at all. The
                                       FOURTH application of this
                                       fleet's format/syntactic-validity
                                       check family.
    4. Protected recipient          -- for `:actuation/disconnect-
                                       service`, INDEPENDENTLY recompute
                                       whether the meter's own recorded
                                       `:protected-recipient?` field is
                                       true (a life-support-equipment or
                                       designated critical-
                                       infrastructure customer) -- ALWAYS
                                       a HARD, un-overridable hold,
                                       regardless of confidence or any
                                       approval. THIS FLEET'S FIRST
                                       protected-recipient check -- see
                                       ns docstring Decision framing in
                                       this repo's own `docs/adr/0001-
                                       architecture.md`.
    5. Billing/service dispute
       unresolved                    -- reported by THIS proposal
                                       itself (a `:dispute/screen` that
                                       just found an unresolved
                                       dispute), or already on file for
                                       the meter (`:dispute/screen`/
                                       `:actuation/disconnect-service`).
                                       Evaluated UNCONDITIONALLY (not
                                       scoped to a specific op), the
                                       SAME discipline `satcom.
                                       governor/coordination-dispute-
                                       unresolved-violations` (`6130`)/
                                       `water.governor/threshold-
                                       breach-unresolved-violations`
                                       (`3600`) establish.
    6. Confidence floor             -- LLM confidence below threshold
                                       -> escalate.

  One more guard pair, double-provisioning/double-disconnection
  prevention, is enforced but NOT listed as a numbered HARD check above
  because it needs no upstream comparison at all --
  `already-provisioned-violations`/`already-disconnected-violations`
  refuse to provision service/disconnect service for the SAME meter
  twice, off dedicated `:service-provisioned?`/`:service-disconnected?`
  facts (never a `:status` value) -- the SAME 'check a dedicated
  boolean, not status' discipline every prior sibling governor's guards
  establish, informed by `cloud-itonami-isic-6492`'s status-lifecycle
  bug (ADR-2607071320).

  ESCALATION (SOFT, human MAY approve past it):
    - `:actuation/disconnect-service` is ALWAYS in `high-stakes` --
      withholding electricity, a necessity service, is never an
      autonomous act, at any phase (see `grid.phase`).
    - `capacity-over-threshold-violations` -- for `:actuation/
      provision-service`, INDEPENDENTLY recompute whether the meter's
      own recorded `:capacity-kw` exceeds `grid.registry/default-
      capacity-threshold-kw` (`grid.registry/capacity-over-
      threshold?`) -- a pure ground-truth check. THIS FLEET'S SECOND
      asymmetric dual-actuation shape (after `cloud-itonami-isic-6391`'s
      `newswire.governor`), but on a NEW dimension: `6391`'s asymmetry
      is op-KIND-driven (`:actuation/distribute` is conditionally
      high-stakes, `:actuation/issue-correction` unconditionally is);
      this actor's asymmetry is VALUE-driven -- `:actuation/provision-
      service` is high-stakes only when the meter's own capacity
      exceeds the threshold, not unconditionally, so a clean, small
      residential/small-commercial connection MAY reach phase-3
      auto-commit while a clean but large connection always escalates
      for a distribution capacity-impact review.
    - low confidence (< `confidence-floor`).

  ── Additive: feeder outage-event logging + restoration reporting ──

  `:actuation/log-outage-event` (feeder-id subject) and `:actuation/
  report-restoration` (outage-id subject) are a SEPARATE dual-
  actuation pair, on a SEPARATE entity (a feeder, not a meter) and a
  SEPARATE evidence catalog (`grid.facts/outage-catalog`, not
  `grid.facts/catalog` -- a feeder is network infrastructure, there is
  no customer identity to verify for an outage event). Both are
  UNCONDITIONALLY `high-stakes` (like `:actuation/disconnect-service`,
  NOT like the value-driven `:actuation/provision-service` asymmetry
  above) -- see `grid.phase`: neither is ever in any phase's `:auto`
  set. `spec-basis-violations`/`evidence-incomplete-violations` above
  are EXTENDED (their op-membership sets grew; their logic for the
  original ops is unchanged) to also gate these two ops against
  `grid.facts/outage-spec-basis`/`grid.facts/outage-evidence-
  satisfied?`. Two NEW double-actuation guards, the SAME 'check a
  dedicated boolean, not status' discipline as `already-provisioned-
  violations`/`already-disconnected-violations`:
    - `already-outage-open-violations` -- `:actuation/log-outage-
      event` for a feeder that already has an open (unrestored)
      outage, off `grid.store/feeder-has-open-outage?`.
    - `restoration-without-open-outage-violations` -- `:actuation/
      report-restoration` whose outage-id does not resolve to a
      currently-open outage (unknown or already restored), off
      `grid.store/outage-open?`.

  Why this exists: a committed outage-event record's `:grid-outage/
  id`/`:grid-outage/source-actor`/`:grid-outage/duration-minutes`
  (`grid.store`'s `outage-of`) is the upstream half of an entirely
  optional, asymmetric, no-shared-code cross-actor contract with
  `cloud-itonami-jsic-4721`'s `coldchain.governor` (a downstream
  physical-operations actor that self-reports its own reefer/
  compressor power-outage duration and MAY independently cross-check
  it against this actor's record) -- see superproject ADR-2608510000.
  This governor has no code path that calls jsic-4721 (or any other
  downstream consumer); it works standalone.

  ── Additive: feeder <-> downstream power-metering reconciliation ──

  `:feeder/log-metering-reading` (feeder-id subject) is a SEPARATE,
  entirely optional, no-shared-code cross-actor contract on the SAME
  (isic-3510, jsic-4721) pair as the outage-event contract above, but
  for STEADY-STATE consumption rather than outage events: this actor
  logs a `:power-metering/*` reading (period + `:consumed-kwh`) a
  downstream physical-operations client MAY independently reconcile
  against ITS OWN registered equipment-assets' rated power draw (see
  superproject ADR-2800001100). `metering-reading-invalid-violations`
  is a NEW HARD check (feeder existence, period ordering, non-negative
  kWh) -- this governor still has no code path that calls the
  downstream client; it works standalone either way."
  (:require [grid.facts :as facts]
            [grid.registry :as registry]
            [grid.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to ALWAYS require a human, even when clean.
  Unlike every SYMMETRIC dual-actuation sibling (`cloud-itonami-isic-
  6190`/`-6120`/`-6130`/`-3600`, where BOTH actuations are permanently
  excluded from auto-commit), this actor's ORIGINAL `high-stakes` set
  had only ONE unconditional member -- `:actuation/disconnect-service`
  -- matching `cloud-itonami-isic-6391`'s own one-member `high-stakes`
  shape. `:actuation/provision-service` is high-stakes only
  conditionally, via `capacity-over-threshold-violations` below (see ns
  docstring). Additive: `:actuation/log-outage-event`/`:actuation/
  report-restoration` (the feeder outage-event pair, see ns docstring)
  are ALSO unconditional members, the SAME symmetric-pair shape
  `:actuation/disconnect-service` uses on its own (meter, service)
  entity, applied here to the (feeder, outage-event) entity instead."
  #{:actuation/disconnect-service :actuation/log-outage-event :actuation/report-restoration})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "An `:identity/verify` (or actuation) proposal with no spec-basis
  citation is a HARD violation -- never invent a jurisdiction's
  electricity-distribution requirements. Additive: `:actuation/log-
  outage-event`/`:actuation/report-restoration` (the feeder
  outage-event pair, see ns docstring) joined this op set -- same
  check, same logic, no change for the original three ops."
  [{:keys [op]} proposal]
  (when (contains? #{:identity/verify :actuation/provision-service :actuation/disconnect-service
                     :actuation/log-outage-event :actuation/report-restoration} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は電気事業法上の需要家保護要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:actuation/provision-service`/`:actuation/disconnect-service`,
  the jurisdiction's required customer-identity-verification-record/
  meter-registration-record/interconnection-capacity-review-record/
  service-disconnection-log evidence must actually be satisfied -- do
  not trust the advisor's self-reported confidence alone.

  Additive: for `:actuation/log-outage-event` (feeder-id subject) /
  `:actuation/report-restoration` (outage-id subject -- resolved to
  its feeder via `store/outage-of`), the SAME check is applied against
  the SEPARATE `grid.facts/outage-catalog`/`grid.facts/outage-
  evidence-satisfied?` (a feeder is not a meter -- see ns docstring).
  `identity-verification-of` is REUSED as-is (its `:verifications` map
  is keyed by an arbitrary id string, so a feeder-id and a meter-id
  never collide) -- no new store method needed for that half. Logic
  for the original two ops is unchanged."
  [{:keys [op subject]} st]
  (cond
    (contains? #{:actuation/provision-service :actuation/disconnect-service} op)
    (let [m (store/meter st subject)
          verification (store/identity-verification-of st subject)]
      (when-not (and verification
                     (facts/required-evidence-satisfied?
                      (:jurisdiction m) (:checklist verification)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(顧客確認記録/計量器登録記録/接続容量審査記録/供給停止台帳等)が充足していない状態での提案"}]))

    (= op :actuation/log-outage-event)
    (let [f (store/feeder st subject)
          verification (store/identity-verification-of st subject)]
      (when-not (and verification
                     (facts/outage-evidence-satisfied?
                      (:jurisdiction f) (:checklist verification)))
        [{:rule :evidence-incomplete
          :detail "法域のoutage事象記録要件(outage-event-log等)が充足していない状態での提案"}]))

    (= op :actuation/report-restoration)
    (let [feeder-id (:feeder-id (store/outage-of st subject))
          f (store/feeder st feeder-id)
          verification (store/identity-verification-of st feeder-id)]
      (when-not (and verification
                     (facts/outage-evidence-satisfied?
                      (:jurisdiction f) (:checklist verification)))
        [{:rule :evidence-incomplete
          :detail "法域のoutage事象記録要件(restoration-time-log等)が充足していない状態での提案"}]))

    :else nil))

(defn- meter-number-format-invalid-violations
  "For `:actuation/provision-service`, INDEPENDENTLY recompute whether
  the meter's own recorded meter number is syntactically valid via
  `grid.registry/meter-number-invalid-format?` -- needs no proposal
  inspection or stored-verdict lookup at all, since its inputs are a
  permanent ground-truth field already on the meter."
  [{:keys [op subject]} st]
  (when (= op :actuation/provision-service)
    (let [m (store/meter st subject)]
      (when (registry/meter-number-invalid-format? m)
        [{:rule :meter-number-format-invalid
          :detail (str subject " の計量器番号(" (:meter-number m) ")は計量器番号(IEC 62056-21準拠 8-12桁数字)形式として不正")}]))))

(defn- protected-recipient-violations
  "For `:actuation/disconnect-service`, INDEPENDENTLY recompute whether
  the meter's own recorded `:protected-recipient?` field is true -- a
  life-support-equipment or designated critical-infrastructure customer
  (hospital, water-treatment plant, emergency-services facility, home
  dialysis/ventilator user, etc.). ALWAYS a HARD, un-overridable hold --
  no confidence level and no human approval can clear it; a genuine
  disconnection for such a meter is a decision this actor never
  performs, full stop. THIS FLEET'S FIRST protected-recipient check --
  no prior sibling actor (including `water.governor`, `cloud-itonami-
  isic-3600`'s own closest infrastructure/utility domain analog) models
  an analogous concept; see this repo's own `docs/adr/0001-
  architecture.md`."
  [{:keys [op subject]} st]
  (when (= op :actuation/disconnect-service)
    (let [m (store/meter st subject)]
      (when (true? (:protected-recipient? m))
        [{:rule :protected-recipient
          :detail (str subject " (" (:customer-name m) ") は生命維持装置使用者/重要インフラ需要家として保護登録済み -- 供給停止は常に不可、承認による解除も不可")}]))))

(defn- dispute-unresolved-violations
  "An unresolved billing/service dispute on the meter -- reported by
  THIS proposal (e.g. a `:dispute/screen` that itself just found one),
  or already on file in the store for the meter (`:dispute/screen`/
  `:actuation/disconnect-service`) -- is a HARD, un-overridable hold.
  Evaluated UNCONDITIONALLY (not scoped to a specific op) so the
  screening op itself can HARD-hold on its own finding. Mirrors
  `satcom.governor/coordination-dispute-unresolved-violations`
  (`6130`)/`water.governor/threshold-breach-unresolved-violations`
  (`3600`)."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :unresolved (get-in proposal [:value :verdict]))
        meter-id (when (contains? #{:dispute/screen :actuation/disconnect-service} op) subject)
        hit-on-file? (and meter-id (= :unresolved (:verdict (store/dispute-screen-of st meter-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :dispute-unresolved
        :detail "未解決の請求・供給紛争がある状態での供給停止提案は進められない"}])))

(defn- already-provisioned-violations
  "For `:actuation/provision-service`, refuses to provision service for
  the SAME meter twice, off a dedicated `:service-provisioned?` fact
  (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :actuation/provision-service)
    (when (store/meter-already-provisioned? st subject)
      [{:rule :already-provisioned
        :detail (str subject " は既に供給開始済み")}])))

(defn- already-disconnected-violations
  "For `:actuation/disconnect-service`, refuses to disconnect service
  for the SAME meter twice, off a dedicated `:service-disconnected?`
  fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :actuation/disconnect-service)
    (when (store/meter-already-disconnected? st subject)
      [{:rule :already-disconnected
        :detail (str subject " は既に供給停止済み")}])))

(defn- already-outage-open-violations
  "For `:actuation/log-outage-event`, refuses to open a SECOND outage
  on the SAME feeder while one is already open, off a dedicated
  `grid.store/feeder-has-open-outage?` boolean (never a `:status`
  value) -- the SAME discipline `already-provisioned-violations` uses,
  applied to (feeder, outage-event) instead of (meter, service)."
  [{:keys [op subject]} st]
  (when (= op :actuation/log-outage-event)
    (when (store/feeder-has-open-outage? st subject)
      [{:rule :already-open-outage
        :detail (str subject " のフィーダーには既に未復旧のoutageイベントが存在する")}])))

(defn- restoration-without-open-outage-violations
  "For `:actuation/report-restoration`, refuses to report a restoration
  whose outage-id does not resolve to a currently-open outage (either
  it never existed, or it was already restored), off a dedicated
  `grid.store/outage-open?` boolean -- the SAME discipline
  `already-disconnected-violations` uses, applied to the closing half
  of the (feeder, outage-event) lifecycle."
  [{:keys [op subject]} st]
  (when (= op :actuation/report-restoration)
    (when-not (store/outage-open? st subject)
      [{:rule :restoration-without-open-outage
        :detail (str subject " は現在未復旧(open)状態のoutageイベントとして見つからない(存在しないか既に復旧済み)")}])))

(defn- metering-reading-invalid-violations
  "For `:feeder/log-metering-reading`, INDEPENDENTLY recompute three
  ground-truth checks against the proposal's own `:power-metering/*`
  value (never trust the advisor's self-reported confidence alone),
  the SAME 'advisor proposes, governor independently re-verifies'
  discipline every other HARD check in this ns uses:

    1. the feeder itself must exist in the store (`grid.store/feeder`)
       -- a metering reading for an unknown feeder-ref is never
       proposable for real infrastructure this actor does not
       recognize.
    2. the reading's own period must be chronologically well-formed
       (`:power-metering/period-end-iso` strictly after `:power-
       metering/period-start-iso`) -- ISO-8601 UTC timestamps in
       canonical zero-padded form compare correctly lexicographically,
       the SAME trick `grid.registry`'s zero-padded sequence numbers
       rely on for ordering, so no date-library dependency is needed
       here either.
    3. `:power-metering/consumed-kwh` must be a non-negative number --
       a negative or non-numeric reading can never be a real physical
       measurement.

  ALWAYS a HARD, un-overridable hold -- a malformed or fabricated
  metering reading must never reach a downstream client (e.g.
  cloud-itonami-jsic-4721) for reconciliation. See superproject
  ADR-2800001100 for the shared `:power-metering/*` wire shape."
  [{:keys [op subject]} proposal st]
  (when (= op :feeder/log-metering-reading)
    (let [f (store/feeder st subject)
          v (:value proposal)
          start (:power-metering/period-start-iso v)
          end (:power-metering/period-end-iso v)
          kwh (:power-metering/consumed-kwh v)]
      (into []
            (remove nil?
                    [(when (nil? f)
                       {:rule :unknown-feeder
                        :detail (str subject " は登録済みフィーダーとして見つからない")})
                     (when-not (and (string? start) (string? end) (pos? (compare end start)))
                       {:rule :invalid-metering-period
                        :detail (str "計量期間の終了(" end ")が開始(" start ")より後になっていない")})
                     (when-not (and (number? kwh) (>= kwh 0))
                       {:rule :invalid-consumed-kwh
                        :detail (str ":consumed-kwh(" kwh ")が0以上の数値でない")})])))))

(defn- capacity-over-threshold-violations
  "For `:actuation/provision-service`, INDEPENDENTLY recompute whether
  the meter's own recorded `:capacity-kw` exceeds `grid.registry/
  default-capacity-threshold-kw` -- a SOFT, human-sign-off escalation
  (a distribution capacity-impact review, not an absolute block: a
  human may review the interconnection study and approve it). Evaluated
  UNCONDITIONALLY off the meter's own permanent field, the SAME
  ground-truth-recompute discipline `meter-number-format-invalid-
  violations` uses."
  [{:keys [op subject]} st]
  (when (= op :actuation/provision-service)
    (boolean (registry/capacity-over-threshold? (store/meter st subject)))))

(defn check
  "Censors a Grid Distribution Advisor proposal against the governor
  rules. Returns {:ok? bool :violations [..] :confidence c :escalate?
  bool :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (meter-number-format-invalid-violations request st)
                           (protected-recipient-violations request st)
                           (dispute-unresolved-violations request proposal st)
                           (already-provisioned-violations request st)
                           (already-disconnected-violations request st)
                           (already-outage-open-violations request st)
                           (restoration-without-open-outage-violations request st)
                           (metering-reading-invalid-violations request proposal st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        over-threshold? (capacity-over-threshold-violations request st)
        stakes? (or (boolean (high-stakes (:stake proposal))) over-threshold?)
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :over-threshold? over-threshold?
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
