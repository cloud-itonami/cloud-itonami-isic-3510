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
  screening/verification op has.")

(def read-ops  #{})
(def write-ops #{:meter/intake :identity/verify :dispute/screen
                 :actuation/provision-service :actuation/disconnect-service})

;; NOTE the invariant: `:actuation/disconnect-service` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member of
;; any phase's `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"        :writes #{}                                                              :auto #{}}
   1 {:label "assisted-intake"  :writes #{:meter/intake}                                                  :auto #{}}
   2 {:label "assisted-verify"  :writes #{:meter/intake :identity/verify :dispute/screen}                 :auto #{}}
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
