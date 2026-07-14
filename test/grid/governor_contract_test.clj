(ns grid.governor-contract-test
  "The governor contract as executable tests -- the electric-
  distribution-utility analog of `cloud-itonami-isic-6130`'s
  `satcom.governor-contract-test`. The single invariant under test:

    Grid Distribution Advisor never provisions service or disconnects
    a meter the Grid Transmission Governor would reject,
    `:actuation/disconnect-service` NEVER auto-commits at any phase, a
    protected (life-support/critical-infrastructure) meter is NEVER
    disconnected regardless of confidence or approval,
    `:actuation/provision-service` MAY auto-commit at phase 3 when
    clean and under the capacity threshold but always escalates when
    over it, `:meter/intake` (no direct capital risk) MAY auto-commit
    when clean, and every decision (commit OR hold) leaves exactly one
    ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [grid.store :as store]
            [grid.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :distribution-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- verify!
  "Walks `subject` through verify -> approve, leaving an identity
  verification on file. Uses distinct thread-ids per call site by
  suffixing `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-verify") {:op :identity/verify :subject subject} operator)
  (approve! actor (str tid-prefix "-verify")))

(defn- screen!
  "Walks `subject` through billing/service-dispute screening -> approve,
  leaving a screening on file. Only safe to call for a meter whose
  dispute status has already resolved -- an unresolved dispute
  HARD-holds the screen itself (see
  `dispute-unresolved-is-held-and-unoverridable`)."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-screen") {:op :dispute/screen :subject subject} operator)
  (approve! actor (str tid-prefix "-screen")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :meter/intake :subject "meter-1"
                   :patch {:id "meter-1" :customer-name "Sakura Ryokan"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Sakura Ryokan" (:customer-name (store/meter db "meter-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest identity-verify-always-needs-approval
  (testing "verify is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :identity/verify :subject "meter-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/identity-verification-of db "meter-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "an identity/verify proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :identity/verify :subject "meter-2" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/identity-verification-of db "meter-2")) "no verification written"))))

(deftest provision-service-without-verification-is-held
  (testing "actuation/provision-service before any identity verification -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :actuation/provision-service :subject "meter-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest meter-number-format-invalid-is-held
  (testing "a meter whose own recorded meter number is malformed -> HOLD"
    (let [[db actor] (fresh)
          _ (verify! actor "t5pre" "meter-3")
          res (exec-op actor "t5" {:op :actuation/provision-service :subject "meter-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:meter-number-format-invalid} (-> (store/ledger db) last :basis)))
      (is (empty? (store/provisioning-history db))))))

(deftest dispute-unresolved-is-held-and-unoverridable
  (testing "an unresolved billing/service dispute on a meter -> HOLD, and never reaches request-approval -- exercised via :dispute/screen DIRECTLY, not via the actuation op against an unscreened meter (see this actor's governor ns docstring / satcom's [6130] own ADR-0001)"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :dispute/screen :subject "meter-6"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:dispute-unresolved} (-> (store/ledger db) first :basis)))
      (is (nil? (store/dispute-screen-of db "meter-6")) "no clearance written"))))

(deftest protected-recipient-disconnect-is-held-and-unoverridable
  (testing "a meter flagged :protected-recipient? (life-support/critical-infrastructure) -> HOLD on disconnect, and NEVER reaches request-approval -- no confidence level and no human approval can clear it"
    (let [[db actor] (fresh)
          _ (verify! actor "t7pre" "meter-4")
          res (exec-op actor "t7" {:op :actuation/disconnect-service :subject "meter-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:protected-recipient} (-> (store/ledger db) last :basis)))
      (is (false? (:service-disconnected? (store/meter db "meter-4"))) "never disconnected"))))

(deftest provision-service-clean-under-threshold-auto-commits-at-phase-3
  (testing "a clean, fully-verified, well-formed, under-threshold-capacity meter AUTO-COMMITS at phase 3 -- no interrupt at all"
    (let [[db actor] (fresh)
          _ (verify! actor "t8pre" "meter-1")
          res (exec-op actor "t8" {:op :actuation/provision-service :subject "meter-1"} operator)]
      (is (not= :interrupted (:status res)) "never pauses for approval")
      (is (= :commit (get-in res [:state :disposition])))
      (is (true? (:service-provisioned? (store/meter db "meter-1"))))
      (is (= 1 (count (store/provisioning-history db))) "one draft provisioning record"))))

(deftest provision-service-over-threshold-always-escalates-then-human-decides
  (testing "a clean, fully-verified, well-formed meter whose own capacity-kw exceeds the threshold still interrupts for human approval, even though :actuation/provision-service is phase-3 auto-eligible in principle"
    (let [[db actor] (fresh)
          _ (verify! actor "t9pre" "meter-5")
          r1 (exec-op actor "t9" {:op :actuation/provision-service :subject "meter-5"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval despite phase-3 auto eligibility")
      (testing "approve -> commit, provisioning record drafted"
        (let [r2 (approve! actor "t9")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:service-provisioned? (store/meter db "meter-5"))))
          (is (= 1 (count (store/provisioning-history db))) "one draft provisioning record"))))))

(deftest disconnect-service-always-escalates-then-human-decides
  (testing "a clean, fully-verified, resolved-dispute, non-protected meter still ALWAYS interrupts for human approval -- actuation/disconnect-service is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t10pre" "meter-1")
          _ (exec-op actor "t10pre2" {:op :actuation/provision-service :subject "meter-1"} operator)
          _ (screen! actor "t10pre3" "meter-1")
          r1 (exec-op actor "t10" {:op :actuation/disconnect-service :subject "meter-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, disconnection record drafted"
        (let [r2 (approve! actor "t10")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:service-disconnected? (store/meter db "meter-1"))))
          (is (= 1 (count (store/disconnection-history db))) "one draft disconnection record"))))))

(deftest provision-service-double-provisioning-is-held
  (testing "provisioning the same meter's service twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t11pre" "meter-1")
          _ (exec-op actor "t11a" {:op :actuation/provision-service :subject "meter-1"} operator)
          res (exec-op actor "t11" {:op :actuation/provision-service :subject "meter-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-provisioned} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/provisioning-history db))) "still only the one earlier provisioning"))))

(deftest disconnect-service-double-disconnection-is-held
  (testing "disconnecting the same meter's service twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t12pre" "meter-1")
          _ (exec-op actor "t12pre2" {:op :actuation/provision-service :subject "meter-1"} operator)
          _ (screen! actor "t12pre3" "meter-1")
          _ (exec-op actor "t12a" {:op :actuation/disconnect-service :subject "meter-1"} operator)
          _ (approve! actor "t12a")
          res (exec-op actor "t12" {:op :actuation/disconnect-service :subject "meter-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-disconnected} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/disconnection-history db))) "still only the one earlier disconnection"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :meter/intake :subject "meter-1"
                          :patch {:id "meter-1" :customer-name "Sakura Ryokan"}} operator)
      (exec-op actor "b" {:op :identity/verify :subject "meter-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
