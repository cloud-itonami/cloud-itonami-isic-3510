(ns grid.phase
  "Phase 0->3 staged rollout -- the electric-distribution-utility analog
  of `cloud-itonami-isic-6130`'s `satcom.phase`.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- meter intake allowed, every write needs
                                 human approval.
    Phase 2  assisted-verify  -- adds identity verification + billing/
                                 service dispute screening writes, still
                                 approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:meter/intake` (no capital risk) AND a
                                 clean, capacity-under-threshold
                                 `:actuation/provision-service` MAY
                                 auto-commit. `:actuation/disconnect-
                                 service` NEVER auto-commits, at ANY
                                 phase.

  `:actuation/provision-service` is a member of phase 3's `:auto` set --
  THIS FLEET'S SECOND asymmetric dual-actuation phase table (after
  `cloud-itonami-isic-6391`'s `newswire.phase`). Whether a given
  `:actuation/provision-service` proposal ACTUALLY auto-commits is
  still gated by `grid.governor/check`: a meter whose own recorded
  `:capacity-kw` exceeds `grid.registry/default-capacity-threshold-kw`
  is high-stakes regardless of this phase membership (see `grid.
  governor/capacity-over-threshold-violations`), so `:auto` membership
  here is necessary but not sufficient -- the SAME 'phase membership
  gates, governor decides' relationship every prior sibling's phase
  table uses.

  `:actuation/disconnect-service` is deliberately ABSENT from every
  phase's `:auto` set, including phase 3 -- a permanent structural
  fact, not a rollout milestone still to come. Disconnecting a real
  customer's electricity supply -- a necessity service -- is always a
  human distribution operator's call. `grid.governor`'s `high-stakes`
  set enforces the same invariant independently -- two layers, not one,
  agree on this. `:identity/verify`/`:dispute/screen` are likewise
  never auto-eligible, at any phase -- the same posture every sibling's
  screening/verification op has.

  ── Additive: feeder outage-event logging + restoration reporting ──

  `:feeder/log-status` (phase 1+, like `:meter/intake`) and `:supply/
  report-status` (phase 2+, like `:identity/verify`/`:dispute/screen`)
  are routine, non-actuation writes -- enabled early, but like
  `:identity/verify`/`:dispute/screen` deliberately NEVER added to any
  phase's `:auto` set either (this V1 always routes them to human
  approval once enabled, a stricter posture than `:meter/intake`'s;
  phase 3's `:auto` set is UNCHANGED by this addition). `:actuation/
  log-outage-event`/`:actuation/report-restoration` (the feeder
  outage-event pair -- see `grid.governor` ns docstring) join
  `write-ops`/phase 3's `:writes` the SAME way `:actuation/disconnect-
  service` does -- available only from phase 3, and (like
  `:actuation/disconnect-service`) deliberately ABSENT from every
  phase's `:auto` set, including phase 3. Do not add them there.

  ── Additive: feeder <-> generator power-supply linkage ──

  `:feeder/register-power-supply` (phase 1+, the SAME early-enabled,
  never-auto posture as `:feeder/log-status`) registers WHICH upstream
  generation actor (`cloud-itonami-isic-3511`/`-3512`) supplies a
  feeder -- see `grid.gridadvisor`/superproject ADR-2800000500. A
  directory fact about an already-agreed arrangement, not a dispatch
  decision; phase 3's `:auto` set is UNCHANGED by this addition.

  ── Additive: feeder <-> downstream power-metering reconciliation ──

  `:feeder/log-metering-reading` (phase 2+, the SAME 'routine report,
  enabled early, never auto' posture as `:supply/report-status`) logs
  a metering reading a downstream client may independently reconcile
  -- see `grid.gridadvisor`/`grid.governor`/superproject
  ADR-2800001100. Placed at phase 2 rather than phase 1 (unlike the
  directory-fact `:feeder/register-power-supply`) because it is closer
  in kind to `:supply/report-status`'s own routine-reporting posture
  than to a one-time administrative linkage; phase 3's `:auto` set is
  UNCHANGED by this addition.")

(def read-ops  #{})
(def write-ops #{:meter/intake :identity/verify :dispute/screen
                 :actuation/provision-service :actuation/disconnect-service
                 :feeder/log-status :actuation/log-outage-event
                 :actuation/report-restoration :supply/report-status
                 :feeder/register-power-supply :feeder/log-metering-reading})

;; NOTE the invariant: `:actuation/disconnect-service` (and, ADDITIVE,
;; `:actuation/log-outage-event`/`:actuation/report-restoration`) are
;; members of `write-ops` (governor-gated like any write) but are NEVER
;; members of any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"        :writes #{}                                                              :auto #{}}
   1 {:label "assisted-intake"  :writes #{:meter/intake :feeder/log-status
                                          :feeder/register-power-supply}                                   :auto #{}}
   2 {:label "assisted-verify"  :writes #{:meter/intake :identity/verify :dispute/screen
                                          :feeder/log-status :supply/report-status
                                          :feeder/register-power-supply :feeder/log-metering-reading}       :auto #{}}
   3 {:label "supervised-auto"  :writes write-ops
      :auto #{:meter/intake :actuation/provision-service}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:actuation/disconnect-service` is never auto-eligible at any
    phase, so it always escalates once the governor clears it (or
    holds if the governor doesn't).
  - `:actuation/provision-service` IS auto-eligible at phase 3, but
    only actually auto-commits when the governor's own verdict was
    already `:commit` (i.e. not high-stakes -- see `grid.governor/
    capacity-over-threshold-violations`)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Grid Transmission Governor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
