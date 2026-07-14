(ns grid.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean meter through
  intake -> identity verification -> billing/service-dispute screening
  -> service-provisioning proposal (clean + under-threshold -> AUTO at
  phase 3) -> service-disconnection proposal (always escalates -> human
  approval -> commit), then shows six HARD holds (a jurisdiction with
  no spec-basis, a malformed meter number, an over-threshold
  provisioning that still requires human sign-off, disconnecting a
  protected life-support/critical-infrastructure meter [never
  overridable], an unresolved billing/service dispute screened directly
  via `:dispute/screen` [never via an actuation op against an
  unscreened meter -- see this actor's own governor ns docstring], and
  a double provisioning/disconnection of an already-processed meter)
  that never reach a human at all, and prints the audit ledger + the
  draft provisioning and disconnection records."
  (:require [langgraph.graph :as g]
            [grid.store :as store]
            [grid.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :distribution-operator :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== meter/intake meter-1 (JPN, clean; valid meter number, no dispute) ==")
    (println (exec! actor "t1" {:op :meter/intake :subject "meter-1"
                                :patch {:id "meter-1" :customer-name "Sakura Ryokan"}} operator))

    (println "== identity/verify meter-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :identity/verify :subject "meter-1"} operator))
    (println (approve! actor "t2"))

    (println "== dispute/screen meter-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :dispute/screen :subject "meter-1"} operator))
    (println (approve! actor "t3"))

    (println "== actuation/provision-service meter-1 (clean, capacity=10kW < threshold -> AUTO-COMMITS at phase 3) ==")
    (println (exec! actor "t4" {:op :actuation/provision-service :subject "meter-1"} operator))

    (println "== actuation/disconnect-service meter-1 (always escalates -- actuation/disconnect-service) ==")
    (let [r (exec! actor "t5" {:op :actuation/disconnect-service :subject "meter-1"} operator)]
      (println r)
      (println "-- human distribution operator approves --")
      (println (approve! actor "t5")))

    (println "== identity/verify meter-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t6" {:op :identity/verify :subject "meter-2" :no-spec? true} operator))

    (println "== identity/verify meter-3 (escalates -- human approves; sets up the malformed-number test) ==")
    (println (exec! actor "t7" {:op :identity/verify :subject "meter-3"} operator))
    (println (approve! actor "t7"))

    (println "== actuation/provision-service meter-3 (\"12AB567\" is not valid meter-number format -> HARD hold) ==")
    (println (exec! actor "t8" {:op :actuation/provision-service :subject "meter-3"} operator))

    (println "== identity/verify meter-5 (escalates -- human approves; sets up the over-threshold test) ==")
    (println (exec! actor "t9" {:op :identity/verify :subject "meter-5"} operator))
    (println (approve! actor "t9"))

    (println "== actuation/provision-service meter-5 (capacity=250kW > threshold -> ESCALATES even though clean) ==")
    (let [r (exec! actor "t10" {:op :actuation/provision-service :subject "meter-5"} operator)]
      (println r)
      (println "-- human distribution operator approves after capacity-impact review --")
      (println (approve! actor "t10")))

    (println "== actuation/disconnect-service meter-4 (protected recipient / life-support -> HARD hold, NEVER overridable) ==")
    (println (exec! actor "t11" {:op :actuation/disconnect-service :subject "meter-4"} operator))

    (println "== dispute/screen meter-6 (unresolved -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t12" {:op :dispute/screen :subject "meter-6"} operator))

    (println "== actuation/provision-service meter-1 AGAIN (double-provisioning -> HARD hold) ==")
    (println (exec! actor "t13" {:op :actuation/provision-service :subject "meter-1"} operator))

    (println "== actuation/disconnect-service meter-1 AGAIN (double-disconnection -> HARD hold) ==")
    (println (exec! actor "t14" {:op :actuation/disconnect-service :subject "meter-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft service-provisioning records ==")
    (doseq [r (store/provisioning-history db)] (println r))

    (println "== draft service-disconnection records ==")
    (doseq [r (store/disconnection-history db)] (println r))))
