(ns grid.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout): this repo previously had NO demo page and no
  generator at all. This namespace drives the REAL actor stack
  (`grid.operation` -> `grid.governor` -> `grid.store`) through a
  scenario adapted from this repo's own `grid.sim` demo driver
  (`clojure -M:dev:run`, confirmed by actually running it before this
  file was written): `grid.sim`'s own ids/ops match `grid.store/demo-
  data`'s seeded meters/feeders exactly, and every disposition it
  produces (commit / escalate+approve / HARD hold, and the exact
  `:rule` on each hold) matches `grid.governor`'s own documented checks
  precisely -- confirmed against the real ledger output of `clojure
  -M:dev:run`, not assumed -- so it was safe to reuse rather than
  author from scratch. This file trims `grid.sim`'s ~23-call walk down
  to a representative subset: two phase-3 auto-commits, five
  always/asymmetrically-escalating ops each followed by a real
  `approve!`, and four distinct HARD-hold reasons that never reach a
  human.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [grid.store :as store]
            [grid.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness (unchanged across every repo
;; in this cluster -- do not rewrite, only copy) -----------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :distribution-operator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach, using ONLY real meter/feeder ids from
  `grid.store/demo-data`:

  meter-1 (JPN, clean, capacity-kw 10 -- under `grid.registry/default-
  capacity-threshold-kw` 50, no billing/service dispute) walks the full
  clean lifecycle: a `:meter/intake` directory-normalization patch is a
  phase-3, no-capital-risk auto-commit (governor clean, `:meter/intake`
  is in phase 3's `:auto` set); `:identity/verify` (JPN has a real
  spec-basis in `grid.facts`) and `:dispute/screen` (clean) each ALWAYS
  escalate and are approved by a human distribution operator;
  `:actuation/provision-service` -- clean AND under the capacity
  threshold -- is the SECOND phase-3 auto-commit (this actor's
  asymmetric dual-actuation rule: provisioning auto-commits only below
  the threshold, never unconditionally); `:actuation/disconnect-
  service` -- the real-world act of withholding a necessity service --
  ALWAYS escalates (never auto, at any phase, independently enforced by
  both `grid.governor/high-stakes` and `grid.phase`) and is approved by
  a human distribution operator, producing one draft service-
  provisioning record (`JPN-PRV-000000`) and one draft service-
  disconnection record (`JPN-DSC-000000`).

  meter-5 (JPN, clean, capacity-kw 250 -- OVER the threshold) is
  verified (escalate+approve) then `:actuation/provision-service` is
  proposed: clean but over-threshold, so it ESCALATES even though
  governor-clean (the SAME asymmetric rule's other branch) and is
  approved by a human distribution operator after a capacity-impact
  review, producing a second draft service-provisioning record
  (`JPN-PRV-000001`).

  feeder-1 (JPN, a SEPARATE entity from any meter -- network
  infrastructure upstream of a customer's meter) is verified against
  `grid.facts/outage-catalog` (escalate+approve), then
  `:actuation/log-outage-event` (an outage-event pair unconditionally
  in `grid.governor/high-stakes`, so it ALWAYS escalates regardless of
  phase) is proposed and approved, producing a draft outage-event
  record (`JPN-OUT-000000`); `:actuation/report-restoration` for that
  outage then ALSO escalates and is approved, producing a draft
  restoration record (`JPN-RST-000000`).

  feeder-2 (JPN) gets a routine, non-actuation `:supply/report-status`
  demand-side status report -- still escalates in this V1 (a stricter
  posture than `:meter/intake`'s), approved.

  Then four DISTINCT HARD-hold reasons, none of which ever reach a
  human (a human approver cannot override a HARD violation):
    - meter-2 (jurisdiction ATL, not in `grid.facts/catalog`):
      `:identity/verify` HARD-holds on `:no-spec-basis` -- the advisor
      may not invent a jurisdiction's electricity-distribution
      requirements.
    - meter-3 (JPN, meter-number \"12AB567\" -- letters, not a valid
      IEC 62056-21-style 8-12-digit numeric serial): verified first
      (clean escalate+approve, so identity evidence is on file and the
      hold below is isolated to the meter-number check alone), then
      `:actuation/provision-service` HARD-holds on `:meter-number-
      format-invalid` -- the governor independently recomputes the
      meter number's own syntactic validity, no proposal inspection
      needed.
    - meter-4 (JPN, seeded with `:protected-recipient? true` -- a
      life-support/critical-infrastructure customer -- AND no identity
      verification on file): `:actuation/disconnect-service` HARD-
      holds on BOTH `:evidence-incomplete` and `:protected-recipient`
      simultaneously -- the protected-recipient check is ALWAYS a HARD,
      un-overridable hold, regardless of confidence or any approval.
    - meter-6 (JPN, seeded with `:billing-dispute-unresolved? true`):
      `:dispute/screen` HARD-holds on `:dispute-unresolved` -- an
      unresolved billing/service dispute blocks progress, un-
      overridably, even though the screening op itself is the one that
      (re)discovers it.
    - feeder-3 (jurisdiction ATL, not in `grid.facts/outage-catalog`
      even though ATL also lacks an entry in `grid.facts/catalog`
      above -- a SEPARATE, honestly-reported coverage gap):
      `:identity/verify` (reused against the outage-reporting catalog
      for a feeder subject) HARD-holds on `:no-spec-basis` -- the SAME
      rule name as meter-2's hold, but a genuinely distinct trigger (a
      different entity, a different catalog).

  Returns the resulting store -- every field `render` below reads is
  real governor/store output, not a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]

    ;; meter-1: clean directory-normalization patch -- phase-3 auto-commit,
    ;; no capital risk yet.
    (exec! actor "m1-intake" {:op :meter/intake :subject "meter-1"
                               :patch {:id "meter-1" :customer-name "Sakura Ryokan"}})

    ;; meter-1: customer-identity + electricity-distribution evidence
    ;; checklist (JPN has a real spec-basis) -- ALWAYS escalates, approved
    ;; by a human distribution operator.
    (exec! actor "m1-verify" {:op :identity/verify :subject "meter-1"})
    (approve! actor "m1-verify")

    ;; meter-1: billing/service dispute screening, clean -- ALWAYS
    ;; escalates, approved by a human distribution operator.
    (exec! actor "m1-dispute" {:op :dispute/screen :subject "meter-1"})
    (approve! actor "m1-dispute")

    ;; meter-1: REAL service provisioning (actuation/provision-service,
    ;; energizing a real connection) -- clean AND under the capacity
    ;; threshold (10kW < 50kW) -> phase-3 AUTO-COMMIT, this actor's
    ;; asymmetric dual-actuation rule.
    (exec! actor "m1-provision" {:op :actuation/provision-service :subject "meter-1"})

    ;; meter-1: REAL service disconnection (actuation/disconnect-service,
    ;; withholding a necessity service) -- ALWAYS escalates regardless of
    ;; phase, approved by a human distribution operator.
    (exec! actor "m1-disconnect" {:op :actuation/disconnect-service :subject "meter-1"})
    (approve! actor "m1-disconnect")

    ;; meter-5: verify first (escalate+approve) so evidence is on file,
    ;; then service provisioning -- clean but capacity 250kW > 50kW
    ;; threshold -> ESCALATES even though governor-clean (the SAME
    ;; asymmetric rule's other branch), approved after a capacity-impact
    ;; review.
    (exec! actor "m5-verify" {:op :identity/verify :subject "meter-5"})
    (approve! actor "m5-verify")
    (exec! actor "m5-provision" {:op :actuation/provision-service :subject "meter-5"})
    (approve! actor "m5-provision")

    ;; meter-2 (ATL): no official spec-basis in grid.facts -> HARD hold on
    ;; :no-spec-basis, never reaches a human.
    (exec! actor "m2-verify" {:op :identity/verify :subject "meter-2"})

    ;; meter-3: verify first (clean escalate+approve) so evidence is on
    ;; file and the meter-number-format hold below is isolated.
    (exec! actor "m3-verify" {:op :identity/verify :subject "meter-3"})
    (approve! actor "m3-verify")

    ;; meter-3: meter-number "12AB567" is not a valid IEC 62056-21-style
    ;; format -> HARD hold on :meter-number-format-invalid, never reaches
    ;; a human.
    (exec! actor "m3-provision" {:op :actuation/provision-service :subject "meter-3"})

    ;; meter-4: seeded with :protected-recipient? true (life-support/
    ;; critical-infrastructure) AND no identity verification on file ->
    ;; HARD hold on :evidence-incomplete AND :protected-recipient
    ;; simultaneously, NEVER overridable by any approval.
    (exec! actor "m4-disconnect" {:op :actuation/disconnect-service :subject "meter-4"})

    ;; meter-6: seeded with :billing-dispute-unresolved? true -> HARD hold
    ;; on :dispute-unresolved, never reaches a human.
    (exec! actor "m6-dispute" {:op :dispute/screen :subject "meter-6"})

    ;; feeder-1: identity/verify reused against grid.facts/outage-catalog
    ;; (a feeder, not a meter) -- ALWAYS escalates, approved.
    (exec! actor "f1-verify" {:op :identity/verify :subject "feeder-1"})
    (approve! actor "f1-verify")

    ;; feeder-1: REAL outage-event logging -- unconditionally high-stakes
    ;; (like actuation/disconnect-service, never auto at any phase),
    ;; approved.
    (exec! actor "f1-outage" {:op :actuation/log-outage-event :subject "feeder-1"
                               :outage-id "outage-1" :cause-category :cause/equipment-failure})
    (approve! actor "f1-outage")

    ;; outage-1: REAL restoration reporting -- also unconditionally
    ;; high-stakes, approved. Closes the (feeder, outage-event) lifecycle.
    (exec! actor "f1-restore" {:op :actuation/report-restoration :subject "outage-1" :duration-minutes 75})
    (approve! actor "f1-restore")

    ;; feeder-2: routine, non-actuation demand-side supply-status report
    ;; -- still escalates in this V1 (a stricter posture than
    ;; :meter/intake's), approved.
    (exec! actor "f2-report" {:op :supply/report-status :subject "feeder-2"})
    (approve! actor "f2-report")

    ;; feeder-3 (ATL): grid.facts/outage-catalog has no entry (a
    ;; SEPARATE, honestly-reported coverage gap from grid.facts/catalog)
    ;; -> HARD hold on :no-spec-basis, never reaches a human.
    (exec! actor "f3-verify" {:op :identity/verify :subject "feeder-3"})

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      (= :approval-rejected (:t f)) "<span class=\"critical\">approval rejected</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- meter-row [ledger {:keys [id customer-name meter-number capacity-kw jurisdiction
                                  protected-recipient? billing-dispute-unresolved?
                                  service-provisioned? service-disconnected?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s / %s</td><td>%s</td></tr>"
          (esc id) (esc customer-name) (esc meter-number) (esc jurisdiction) (esc capacity-kw)
          (if protected-recipient? "<span class=\"critical\">protected</span>" "<span class=\"muted\">n/a</span>")
          (if billing-dispute-unresolved? "<span class=\"critical\">unresolved</span>" "<span class=\"ok\">clear</span>")
          (if service-provisioned? "provisioned" "not provisioned") (if service-disconnected? "disconnected" "not disconnected")
          (status-cell ledger id)))

(defn- feeder-row [ledger {:keys [id substation-id jurisdiction status]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc substation-id) (esc jurisdiction) (esc (name (or status :unknown)))
          (status-cell ledger id)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map #(if (keyword? %) (name %) %)) (str/join ", "))
                    (some-> disposition name) ""))))

(defn- meter-record-row [prefix {:strs [record_id meter_id jurisdiction kind immutable]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc prefix) (esc record_id) (esc meter_id) (esc jurisdiction)
          (if immutable "<span class=\"ok\">immutable draft</span>" (esc kind))))

(defn- outage-record-row [prefix ref-field {:strs [record_id jurisdiction kind immutable] :as record}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc prefix) (esc record_id) (esc (get record ref-field)) (esc jurisdiction)
          (if immutable "<span class=\"ok\">immutable draft</span>" (esc kind))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract
  ;; (`grid.governor`/`grid.phase`) -- documentation of fixed behavior,
  ;; not runtime telemetry, so it is legitimately hand-described rather
  ;; than derived from a live run.
  ["        <tr><td><code>:meter/intake</code></td><td><span class=\"ok\">phase-3 auto-commit when clean, no capital risk yet</span></td></tr>"
   "        <tr><td><code>:identity/verify</code></td><td><span class=\"warn\">ALWAYS human approval &middot; spec-basis independently checked against <code>grid.facts</code>, never fabricated (also serves feeder outage-event verification against the separate <code>outage-catalog</code>)</span></td></tr>"
   "        <tr><td><code>:dispute/screen</code></td><td><span class=\"warn\">ALWAYS human approval when clean &middot; an unresolved billing/service dispute is a HARD, un-overridable hold instead</span></td></tr>"
   "        <tr><td><code>:actuation/provision-service</code></td><td><span class=\"ok\">phase-3 auto-commit when clean AND under the capacity threshold</span> &middot; <span class=\"warn\">escalates to human capacity-impact review when clean but over-threshold</span> &middot; meter-number format independently re-verified, never auto if invalid</td></tr>"
   "        <tr><td><code>:actuation/disconnect-service</code></td><td><span class=\"warn\">ALWAYS human approval</span> &middot; withholding a necessity service, never auto at any phase &middot; protected-recipient (life-support/critical-infrastructure) status is a HARD, un-overridable hold</td></tr>"
   "        <tr><td><code>:actuation/log-outage-event</code> / <code>:actuation/report-restoration</code></td><td><span class=\"warn\">ALWAYS human approval</span> &middot; unconditionally high-stakes, like disconnection &middot; double-open-outage / restoration-without-open-outage independently guarded</td></tr>"
   "        <tr><td><code>:supply/report-status</code></td><td><span class=\"warn\">ALWAYS human approval in this V1</span> &middot; routine, non-actuation demand-side report</td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        meters (store/all-meters db)
        feeders (store/all-feeders db)
        meter-rows (str/join "\n" (map (partial meter-row ledger) meters))
        feeder-rows (str/join "\n" (map (partial feeder-row ledger) feeders))
        ledger-rows (str/join "\n" (map ledger-row ledger))
        provisioning-rows (str/join "\n" (map (partial meter-record-row "provisioning") (store/provisioning-history db)))
        disconnection-rows (str/join "\n" (map (partial meter-record-row "disconnection") (store/disconnection-history db)))
        outage-rows (str/join "\n" (map (partial outage-record-row "outage-event" "feeder_id") (store/outage-history db)))
        restoration-rows (str/join "\n" (map (partial outage-record-row "restoration" "outage_id") (store/restoration-history db)))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-3510 &middot; electric power generation, transmission and distribution</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Electric power generation, transmission and distribution (ISIC 3510) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · disconnection and outage-event actuation always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Meters</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>grid.store</code> via <code>grid.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Meter</th><th>Customer</th><th>Meter number</th><th>Jurisdiction</th><th>Capacity (kW)</th><th>Protected recipient</th><th>Billing/service dispute</th><th>Provisioning / disconnection</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     meter-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Feeders / substations</h2>\n"
     "    <p class=\"muted\">Network infrastructure upstream of any one customer's meter — a separate entity from a meter, sharing the same identity-verification and audit mechanism.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Feeder</th><th>Substation</th><th>Jurisdiction</th><th>Status</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     feeder-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Draft service-provisioning / service-disconnection records</h2>\n"
     "    <p class=\"muted\">Unsigned drafts only — the licensed distribution utility's own act of signing/energizing is outside this actor's authority (see README <code>Actuation</code>).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Kind</th><th>Record id</th><th>Meter</th><th>Jurisdiction</th><th>Status</th></tr></thead>\n"
     "      <tbody>\n"
     provisioning-rows (when (seq provisioning-rows) "\n")
     disconnection-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Draft outage-event / restoration records</h2>\n"
     "    <p class=\"muted\">Unsigned drafts only — the upstream half of an entirely optional, no-shared-code cross-actor contract with cloud-itonami-jsic-4721 (see superproject ADR-2608510000).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Kind</th><th>Record id</th><th>Feeder / outage ref</th><th>Jurisdiction</th><th>Status</th></tr></thead>\n"
     "      <tbody>\n"
     outage-rows (when (seq outage-rows) "\n")
     restoration-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Grid Transmission Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden by a human approver. Jurisdiction spec-basis, evidence completeness, meter-number format, protected-recipient status and unresolved billing/service disputes are independently recomputed, never trusted from the advisor's proposal; a real service disconnection or outage-event actuation is always a human distribution operator's call, at every rollout phase.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/provisioning-history db)) "provisioning drafts,"
             (count (store/disconnection-history db)) "disconnection drafts,"
             (count (store/outage-history db)) "outage-event drafts,"
             (count (store/restoration-history db)) "restoration drafts )")))
