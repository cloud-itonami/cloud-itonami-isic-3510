(ns grid.store
  "SSoT for the electric-distribution-utility actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite -- the same seam
  every prior `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/grid/store_contract_test.clj), which is the whole point: the
  actor, the Grid Transmission Governor and the audit ledger never know
  which SSoT they run on.

  Like `satcom.store`'s (`cloud-itonami-isic-6130`) dual provisioning/
  suspension history, this actor has TWO actuation events (provisioning
  service, disconnecting service) acting on the SAME entity (a meter),
  each with its OWN history collection, sequence counter and dedicated
  double-actuation-guard boolean (`:service-provisioned?`/`:service-
  disconnected?`, never a `:status` value) -- the same discipline every
  prior sibling governor's guards establish, informed by `cloud-
  itonami-isic-6492`'s status-lifecycle bug (ADR-2607071320).

  The ledger stays append-only on every backend: 'which meter was
  screened for an unresolved billing/service dispute, which meter
  number was provisioned, which service was disconnected, on what
  jurisdictional basis, approved by whom' is always a query over an
  immutable log -- the audit trail a community trusting a distribution
  utility needs, and the evidence a utility needs if a provisioning or
  disconnection decision is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [grid.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (meter [s id])
  (all-meters [s])
  (dispute-screen-of [s meter-id] "committed billing/service-dispute screening verdict for a meter, or nil")
  (identity-verification-of [s meter-id] "committed identity verification, or nil")
  (ledger [s])
  (provisioning-history [s] "the append-only service-provisioning history (grid.registry drafts)")
  (disconnection-history [s] "the append-only service-disconnection history (grid.registry drafts)")
  (next-provisioning-sequence [s jurisdiction] "next provisioning-number sequence for a jurisdiction")
  (next-disconnection-sequence [s jurisdiction] "next disconnection-number sequence for a jurisdiction")
  (meter-already-provisioned? [s meter-id] "has this meter's service already been provisioned?")
  (meter-already-disconnected? [s meter-id] "has this meter's service already been disconnected?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-meters [s meters] "replace/seed the meter directory (map id->meter)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained meter set covering both actuation lifecycles
  (provisioning service, disconnecting service) so the actor + tests
  run offline. `meter-4` carries `:protected-recipient?` true (a
  life-support/critical-infrastructure customer -- see `grid.governor`'s
  `protected-recipient-violations`), `meter-5` carries a `:capacity-kw`
  above `grid.registry/default-capacity-threshold-kw`."
  []
  {:meters
   {"meter-1" {:id "meter-1" :customer-name "Sakura Ryokan"
               :meter-number "10234567"
               :capacity-kw 10
               :protected-recipient? false
               :billing-dispute-unresolved? false
               :service-provisioned? false :service-disconnected? false
               :jurisdiction "JPN" :status :intake}
    "meter-2" {:id "meter-2" :customer-name "Atlantis Research Station"
               :meter-number "10234568"
               :capacity-kw 12
               :protected-recipient? false
               :billing-dispute-unresolved? false
               :service-provisioned? false :service-disconnected? false
               :jurisdiction "ATL" :status :intake}
    "meter-3" {:id "meter-3" :customer-name "鈴木商店"
               :meter-number "12AB567" ; malformed -- letters not allowed
               :capacity-kw 8
               :protected-recipient? false
               :billing-dispute-unresolved? false
               :service-provisioned? false :service-disconnected? false
               :jurisdiction "JPN" :status :intake}
    "meter-4" {:id "meter-4" :customer-name "田中在宅酸素療法宅"
               :meter-number "10234569"
               :capacity-kw 6
               :protected-recipient? true
               :billing-dispute-unresolved? false
               :service-provisioned? true :service-disconnected? false
               :jurisdiction "JPN" :status :active}
    "meter-5" {:id "meter-5" :customer-name "Sakura Foundry Works"
               :meter-number "10234570"
               :capacity-kw 250
               :protected-recipient? false
               :billing-dispute-unresolved? false
               :service-provisioned? false :service-disconnected? false
               :jurisdiction "JPN" :status :intake}
    "meter-6" {:id "meter-6" :customer-name "田中離島診療所"
               :meter-number "10234571"
               :capacity-kw 15
               :protected-recipient? false
               :billing-dispute-unresolved? true
               :service-provisioned? true :service-disconnected? false
               :jurisdiction "JPN" :status :active}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- provision-service!
  "Backend-agnostic `:meter/mark-provisioned` -- looks up the meter via
  the protocol and drafts the service-provisioning record, and returns
  {:result .. :meter-patch ..} for the caller to persist."
  [s meter-id]
  (let [m (meter s meter-id)
        seq-n (next-provisioning-sequence s (:jurisdiction m))
        result (registry/register-service-provisioning meter-id (:jurisdiction m) seq-n)]
    {:result result
     :meter-patch {:service-provisioned? true
                   :provisioning-number (get result "provisioning_number")}}))

(defn- disconnect-service!
  "Backend-agnostic `:meter/mark-disconnected` -- looks up the meter via
  the protocol and drafts the service-disconnection record, and returns
  {:result .. :meter-patch ..} for the caller to persist."
  [s meter-id]
  (let [m (meter s meter-id)
        seq-n (next-disconnection-sequence s (:jurisdiction m))
        result (registry/register-service-disconnection meter-id (:jurisdiction m) seq-n)]
    {:result result
     :meter-patch {:service-disconnected? true
                   :disconnection-number (get result "disconnection_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (meter [_ id] (get-in @a [:meters id]))
  (all-meters [_] (sort-by :id (vals (:meters @a))))
  (dispute-screen-of [_ id] (get-in @a [:dispute-screens id]))
  (identity-verification-of [_ meter-id] (get-in @a [:verifications meter-id]))
  (ledger [_] (:ledger @a))
  (provisioning-history [_] (:provisionings @a))
  (disconnection-history [_] (:disconnections @a))
  (next-provisioning-sequence [_ jurisdiction] (get-in @a [:provisioning-sequences jurisdiction] 0))
  (next-disconnection-sequence [_ jurisdiction] (get-in @a [:disconnection-sequences jurisdiction] 0))
  (meter-already-provisioned? [_ meter-id] (boolean (get-in @a [:meters meter-id :service-provisioned?])))
  (meter-already-disconnected? [_ meter-id] (boolean (get-in @a [:meters meter-id :service-disconnected?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :meter/upsert
      (swap! a update-in [:meters (:id value)] merge value)

      :verification/set
      (swap! a assoc-in [:verifications (first path)] payload)

      :dispute-screen/set
      (swap! a assoc-in [:dispute-screens (first path)] payload)

      :meter/mark-provisioned
      (let [meter-id (first path)
            {:keys [result meter-patch]} (provision-service! s meter-id)
            jurisdiction (:jurisdiction (meter s meter-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:provisioning-sequences jurisdiction] (fnil inc 0))
                       (update-in [:meters meter-id] merge meter-patch)
                       (update :provisionings registry/append result))))
        result)

      :meter/mark-disconnected
      (let [meter-id (first path)
            {:keys [result meter-patch]} (disconnect-service! s meter-id)
            jurisdiction (:jurisdiction (meter s meter-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:disconnection-sequences jurisdiction] (fnil inc 0))
                       (update-in [:meters meter-id] merge meter-patch)
                       (update :disconnections registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-meters [s meters] (when (seq meters) (swap! a assoc :meters meters)) s))

(defn seed-db
  "A MemStore seeded with the demo meter set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :verifications {} :dispute-screens {} :ledger [] :provisioning-sequences {}
                           :provisionings [] :disconnection-sequences {} :disconnections []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (verification/dispute-screen payloads, ledger
  facts, provisioning/disconnection records) are stored as EDN strings
  so `langchain.db` doesn't expand them into sub-entities -- the same
  convention every sibling actor's store uses."
  {:meter/id                          {:db/unique :db.unique/identity}
   :verification/meter-id             {:db/unique :db.unique/identity}
   :dispute-screen/meter-id           {:db/unique :db.unique/identity}
   :ledger/seq                        {:db/unique :db.unique/identity}
   :provisioning/seq                  {:db/unique :db.unique/identity}
   :disconnection/seq                 {:db/unique :db.unique/identity}
   :provisioning-sequence/jurisdiction {:db/unique :db.unique/identity}
   :disconnection-sequence/jurisdiction {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- meter->tx [{:keys [id customer-name meter-number capacity-kw
                          protected-recipient? billing-dispute-unresolved?
                          service-provisioned? service-disconnected?
                          jurisdiction status provisioning-number disconnection-number]}]
  (cond-> {:meter/id id}
    customer-name                            (assoc :meter/customer-name customer-name)
    meter-number                             (assoc :meter/meter-number meter-number)
    (some? capacity-kw)                      (assoc :meter/capacity-kw capacity-kw)
    (some? protected-recipient?)             (assoc :meter/protected-recipient? protected-recipient?)
    (some? billing-dispute-unresolved?)      (assoc :meter/billing-dispute-unresolved? billing-dispute-unresolved?)
    (some? service-provisioned?)             (assoc :meter/service-provisioned? service-provisioned?)
    (some? service-disconnected?)            (assoc :meter/service-disconnected? service-disconnected?)
    jurisdiction                             (assoc :meter/jurisdiction jurisdiction)
    status                                   (assoc :meter/status status)
    provisioning-number                      (assoc :meter/provisioning-number provisioning-number)
    disconnection-number                     (assoc :meter/disconnection-number disconnection-number)))

(def ^:private meter-pull
  [:meter/id :meter/customer-name :meter/meter-number :meter/capacity-kw
   :meter/protected-recipient? :meter/billing-dispute-unresolved?
   :meter/service-provisioned? :meter/service-disconnected?
   :meter/jurisdiction :meter/status :meter/provisioning-number :meter/disconnection-number])

(defn- pull->meter [m]
  (when (:meter/id m)
    {:id (:meter/id m) :customer-name (:meter/customer-name m)
     :meter-number (:meter/meter-number m)
     :capacity-kw (:meter/capacity-kw m)
     :protected-recipient? (boolean (:meter/protected-recipient? m))
     :billing-dispute-unresolved? (boolean (:meter/billing-dispute-unresolved? m))
     :service-provisioned? (boolean (:meter/service-provisioned? m))
     :service-disconnected? (boolean (:meter/service-disconnected? m))
     :jurisdiction (:meter/jurisdiction m) :status (:meter/status m)
     :provisioning-number (:meter/provisioning-number m) :disconnection-number (:meter/disconnection-number m)}))

(defrecord DatomicStore [conn]
  Store
  (meter [_ id]
    (pull->meter (d/pull (d/db conn) meter-pull [:meter/id id])))
  (all-meters [_]
    (->> (d/q '[:find [?id ...] :where [?e :meter/id ?id]] (d/db conn))
         (map #(pull->meter (d/pull (d/db conn) meter-pull [:meter/id %])))
         (sort-by :id)))
  (dispute-screen-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?mid
                :where [?k :dispute-screen/meter-id ?mid] [?k :dispute-screen/payload ?p]]
              (d/db conn) id)))
  (identity-verification-of [_ meter-id]
    (dec* (d/q '[:find ?p . :in $ ?mid
                :where [?a :verification/meter-id ?mid] [?a :verification/payload ?p]]
              (d/db conn) meter-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (provisioning-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :provisioning/seq ?s] [?e :provisioning/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (disconnection-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :disconnection/seq ?s] [?e :disconnection/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-provisioning-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :provisioning-sequence/jurisdiction ?j] [?e :provisioning-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-disconnection-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :disconnection-sequence/jurisdiction ?j] [?e :disconnection-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (meter-already-provisioned? [s meter-id]
    (boolean (:service-provisioned? (meter s meter-id))))
  (meter-already-disconnected? [s meter-id]
    (boolean (:service-disconnected? (meter s meter-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :meter/upsert
      (d/transact! conn [(meter->tx value)])

      :verification/set
      (d/transact! conn [{:verification/meter-id (first path) :verification/payload (enc payload)}])

      :dispute-screen/set
      (d/transact! conn [{:dispute-screen/meter-id (first path) :dispute-screen/payload (enc payload)}])

      :meter/mark-provisioned
      (let [meter-id (first path)
            {:keys [result meter-patch]} (provision-service! s meter-id)
            jurisdiction (:jurisdiction (meter s meter-id))
            next-n (inc (next-provisioning-sequence s jurisdiction))]
        (d/transact! conn
                     [(meter->tx (assoc meter-patch :id meter-id))
                      {:provisioning-sequence/jurisdiction jurisdiction :provisioning-sequence/next next-n}
                      {:provisioning/seq (count (provisioning-history s)) :provisioning/record (enc (get result "record"))}])
        result)

      :meter/mark-disconnected
      (let [meter-id (first path)
            {:keys [result meter-patch]} (disconnect-service! s meter-id)
            jurisdiction (:jurisdiction (meter s meter-id))
            next-n (inc (next-disconnection-sequence s jurisdiction))]
        (d/transact! conn
                     [(meter->tx (assoc meter-patch :id meter-id))
                      {:disconnection-sequence/jurisdiction jurisdiction :disconnection-sequence/next next-n}
                      {:disconnection/seq (count (disconnection-history s)) :disconnection/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-meters [s meters]
    (when (seq meters) (d/transact! conn (mapv meter->tx (vals meters)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:meters ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [meters]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-meters s meters))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo meter set -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
