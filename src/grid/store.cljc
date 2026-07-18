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
  disconnection decision is later disputed.

  ── Additive: feeders + outage-event/restoration tracking ──

  A `feeder` is a SEPARATE entity from a `meter` -- network
  infrastructure (a distribution feeder/substation) upstream of any one
  customer's meter, not a customer-facing entity. Feeders reuse the
  SAME `identity-verification-of`/`:verification/set` mechanism meters
  use (the `:verifications` map is keyed by an arbitrary id string, so
  a feeder-id and a meter-id never collide -- no new protocol method
  needed for that half). What IS new: `all-feeders`/`feeder`, and a
  dual outage-event/restoration history+sequence-counter+double-
  actuation-guard triple (`feeder-has-open-outage?`/`outage-open?`,
  never a `:status` value) -- the SAME discipline `meter-already-
  provisioned?`/`meter-already-disconnected?` already establish for
  meters, applied to the (feeder, outage-event) pair instead of
  (meter, service). See superproject ADR-2608510000 for why: a
  committed outage-event record's `:grid-outage/id`/`:grid-outage/
  source-actor`/`:grid-outage/duration-minutes` fields are the shared,
  no-code, optional wire shape `cloud-itonami-jsic-4721`'s
  `coldchain.governor` independently cross-checks its own self-reported
  `:lot/power-outage-minutes` against.

  ── Additive: feeder <-> generator power-supply linkage ──

  A feeder record MAY also carry `:power-supply/id`/`:power-supply/
  source-actor`/`:power-supply/feeder-ref`/`:power-supply/capacity-mw`/
  `:power-supply/agreement-start-iso` -- the OTHER half of the SAME
  kind of no-shared-code, flat cross-actor wire shape as `:grid-outage/
  *` above, this time upstream: it names which generation actor
  (`cloud-itonami-isic-3511` SMR or `cloud-itonami-isic-3512` community
  renewable) supplies this feeder. See `grid.gridadvisor/register-
  power-supply` and superproject ADR-2800000500. Entirely optional and
  additive -- `:feeder/upsert` (already this actor's generic feeder
  directory-patch effect) carries these fields when present; a feeder
  with none of them behaves exactly as it did before this addition."
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
  (with-meters [s meters] "replace/seed the meter directory (map id->meter)")
  ;; ---- additive: feeders + outage-event/restoration tracking ----
  (feeder [s id] "a feeder/substation record, or nil")
  (all-feeders [s] "all feeder/substation records, sorted by id")
  (outage-of [s outage-id] "the committed outage-event record for this id, or nil")
  (outage-history [s] "the append-only outage-event-logging history (grid.registry drafts)")
  (restoration-history [s] "the append-only restoration-reporting history (grid.registry drafts)")
  (next-outage-sequence [s jurisdiction] "next outage-number sequence for a jurisdiction")
  (next-restoration-sequence [s jurisdiction] "next restoration-number sequence for a jurisdiction")
  (feeder-has-open-outage? [s feeder-id] "does this feeder currently have an open (unrestored) outage?")
  (outage-open? [s outage-id] "is this outage-event id currently open (unrestored)? unknown ids are never open")
  (with-feeders [s feeders] "replace/seed the feeder directory (map id->feeder)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained meter set covering both actuation lifecycles
  (provisioning service, disconnecting service) so the actor + tests
  run offline. `meter-4` carries `:protected-recipient?` true (a
  life-support/critical-infrastructure customer -- see `grid.governor`'s
  `protected-recipient-violations`), `meter-5` carries a `:capacity-kw`
  above `grid.registry/default-capacity-threshold-kw`.

  `:feeders` (additive) is a small, SEPARATE demo set covering the
  outage-event/restoration lifecycle: `feeder-1`/`feeder-2` are JPN
  (this catalog's most fully-covered jurisdiction, both `grid.facts/
  catalog` and `grid.facts/outage-catalog`), `feeder-3` is ATL --
  `grid.facts/outage-catalog` deliberately has NO entry for ATL, the
  same 'no fabricated jurisdiction' failure mode `meter-2` already
  exercises for the meter side."
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
               :jurisdiction "JPN" :status :active}}
   :feeders
   {"feeder-1" {:id "feeder-1" :substation-id "SS-01" :jurisdiction "JPN" :status :in-service}
    "feeder-2" {:id "feeder-2" :substation-id "SS-02" :jurisdiction "JPN" :status :in-service}
    "feeder-3" {:id "feeder-3" :substation-id "SS-99" :jurisdiction "ATL" :status :in-service}}})

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

(defn- log-outage-event!
  "Backend-agnostic `:feeder/mark-outage-logged` (additive) -- looks up
  the feeder via the protocol and drafts the outage-event record.
  Returns {:result .. :outage-record ..} for the caller to persist;
  `:outage-record` is the NEW open-outage entry keyed by `outage-id`
  (feeder-id, jurisdiction, `:open? true`, `:grid-outage/*` fields
  nil-duration until restored) -- the SAME shape both `MemStore` and
  `DatomicStore` write."
  [s feeder-id outage-id cause-category]
  (let [f (feeder s feeder-id)
        seq-n (next-outage-sequence s (:jurisdiction f))
        result (registry/register-outage-event feeder-id outage-id (:jurisdiction f) cause-category seq-n)]
    {:result result
     :outage-record {:id outage-id :feeder-id feeder-id :jurisdiction (:jurisdiction f)
                      :cause-category cause-category :open? true
                      :grid-outage/id outage-id
                      :grid-outage/source-actor "cloud-itonami-isic-3510"
                      :grid-outage/duration-minutes nil}}))

(defn- report-restoration!
  "Backend-agnostic `:feeder/mark-restored` (additive) -- looks up the
  open outage-event via the protocol and drafts the restoration
  record. Returns {:result .. :outage-patch ..} for the caller to
  persist; `:outage-patch` closes the outage (`:open? false`) and sets
  `:grid-outage/duration-minutes` -- the field cloud-itonami-jsic-4721
  cross-checks its own self-reported `:lot/power-outage-minutes`
  against (see this ns's own docstring)."
  [s outage-id duration-minutes]
  (let [o (outage-of s outage-id)
        seq-n (next-restoration-sequence s (:jurisdiction o))
        result (registry/register-outage-restoration outage-id (:jurisdiction o) seq-n)]
    {:result result
     :outage-patch {:open? false :grid-outage/duration-minutes duration-minutes}}))

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
  (feeder [_ id] (get-in @a [:feeders id]))
  (all-feeders [_] (sort-by :id (vals (:feeders @a))))
  (outage-of [_ outage-id] (get-in @a [:outages outage-id]))
  (outage-history [_] (:outage-logs @a))
  (restoration-history [_] (:restorations @a))
  (next-outage-sequence [_ jurisdiction] (get-in @a [:outage-sequences jurisdiction] 0))
  (next-restoration-sequence [_ jurisdiction] (get-in @a [:restoration-sequences jurisdiction] 0))
  (feeder-has-open-outage? [_ feeder-id]
    (boolean (some #(and (= feeder-id (:feeder-id %)) (:open? %)) (vals (:outages @a)))))
  (outage-open? [_ outage-id] (boolean (:open? (get-in @a [:outages outage-id]))))
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

      ;; ---- additive: feeder/outage-event tracking ----
      :feeder/upsert
      (swap! a update-in [:feeders (:id value)] merge value)

      :feeder/mark-outage-logged
      (let [feeder-id (first path)
            outage-id (:outage-id value)
            cause-category (:cause-category value)
            {:keys [result outage-record]} (log-outage-event! s feeder-id outage-id cause-category)
            jurisdiction (:jurisdiction (feeder s feeder-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:outage-sequences jurisdiction] (fnil inc 0))
                       (assoc-in [:outages outage-id] outage-record)
                       (update :outage-logs registry/append result))))
        result)

      :feeder/mark-restored
      (let [outage-id (first path)
            duration-minutes (:duration-minutes value)
            {:keys [result outage-patch]} (report-restoration! s outage-id duration-minutes)
            jurisdiction (:jurisdiction (outage-of s outage-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:restoration-sequences jurisdiction] (fnil inc 0))
                       (update-in [:outages outage-id] merge outage-patch)
                       (update :restorations registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-meters [s meters] (when (seq meters) (swap! a assoc :meters meters)) s)
  (with-feeders [s feeders] (when (seq feeders) (swap! a assoc :feeders feeders)) s))

(defn seed-db
  "A MemStore seeded with the demo meter+feeder set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :verifications {} :dispute-screens {} :ledger [] :provisioning-sequences {}
                           :provisionings [] :disconnection-sequences {} :disconnections []
                           :outages {} :outage-sequences {} :outage-logs []
                           :restoration-sequences {} :restorations []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (verification/dispute-screen payloads, ledger
  facts, provisioning/disconnection/outage/restoration records) are
  stored as EDN strings so `langchain.db` doesn't expand them into
  sub-entities -- the same convention every sibling actor's store
  uses."
  {:meter/id                          {:db/unique :db.unique/identity}
   :verification/meter-id             {:db/unique :db.unique/identity}
   :dispute-screen/meter-id           {:db/unique :db.unique/identity}
   :ledger/seq                        {:db/unique :db.unique/identity}
   :provisioning/seq                  {:db/unique :db.unique/identity}
   :disconnection/seq                 {:db/unique :db.unique/identity}
   :provisioning-sequence/jurisdiction {:db/unique :db.unique/identity}
   :disconnection-sequence/jurisdiction {:db/unique :db.unique/identity}
   ;; ---- additive: feeders + outage-event/restoration tracking ----
   :feeder/id                         {:db/unique :db.unique/identity}
   :outage/id                         {:db/unique :db.unique/identity}
   :outage-log/seq                    {:db/unique :db.unique/identity}
   :restoration/seq                   {:db/unique :db.unique/identity}
   :outage-sequence/jurisdiction      {:db/unique :db.unique/identity}
   :restoration-sequence/jurisdiction {:db/unique :db.unique/identity}})

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

;; ---- additive: feeder + outage-event tx/pull helpers ----

(defn- feeder->tx
  "Additive: `:power-supply/*` (see `grid.gridadvisor/register-power-
  supply`, superproject ADR-2800000500) is OPTIONAL -- a feeder with
  none of these fields round-trips exactly as it did before this
  addition."
  [{:keys [id substation-id jurisdiction status] :as feeder}]
  (cond-> {:feeder/id id}
    substation-id (assoc :feeder/substation-id substation-id)
    jurisdiction  (assoc :feeder/jurisdiction jurisdiction)
    status        (assoc :feeder/status status)
    (:power-supply/id feeder)
    (assoc :feeder/power-supply-id (:power-supply/id feeder))
    (:power-supply/source-actor feeder)
    (assoc :feeder/power-supply-source-actor (:power-supply/source-actor feeder))
    (:power-supply/feeder-ref feeder)
    (assoc :feeder/power-supply-feeder-ref (:power-supply/feeder-ref feeder))
    (some? (:power-supply/capacity-mw feeder))
    (assoc :feeder/power-supply-capacity-mw (:power-supply/capacity-mw feeder))
    (:power-supply/agreement-start-iso feeder)
    (assoc :feeder/power-supply-agreement-start-iso (:power-supply/agreement-start-iso feeder))))

(def ^:private feeder-pull
  [:feeder/id :feeder/substation-id :feeder/jurisdiction :feeder/status
   :feeder/power-supply-id :feeder/power-supply-source-actor
   :feeder/power-supply-feeder-ref :feeder/power-supply-capacity-mw
   :feeder/power-supply-agreement-start-iso])

(defn- pull->feeder [f]
  (when (:feeder/id f)
    (cond-> {:id (:feeder/id f) :substation-id (:feeder/substation-id f)
             :jurisdiction (:feeder/jurisdiction f) :status (:feeder/status f)}
      (:feeder/power-supply-id f)
      (assoc :power-supply/id (:feeder/power-supply-id f))
      (:feeder/power-supply-source-actor f)
      (assoc :power-supply/source-actor (:feeder/power-supply-source-actor f))
      (:feeder/power-supply-feeder-ref f)
      (assoc :power-supply/feeder-ref (:feeder/power-supply-feeder-ref f))
      (some? (:feeder/power-supply-capacity-mw f))
      (assoc :power-supply/capacity-mw (:feeder/power-supply-capacity-mw f))
      (:feeder/power-supply-agreement-start-iso f)
      (assoc :power-supply/agreement-start-iso (:feeder/power-supply-agreement-start-iso f)))))

(defn- outage->tx [{:keys [id feeder-id jurisdiction cause-category open?]
                     :as outage}]
  {:outage/id id
   :outage/feeder-id feeder-id
   :outage/jurisdiction jurisdiction
   :outage/cause-category (str cause-category)
   :outage/open? (boolean open?)
   :outage/duration-minutes (:grid-outage/duration-minutes outage)})

(def ^:private outage-pull
  [:outage/id :outage/feeder-id :outage/jurisdiction :outage/cause-category
   :outage/open? :outage/duration-minutes])

(defn- pull->outage [o]
  (when (:outage/id o)
    {:id (:outage/id o) :feeder-id (:outage/feeder-id o)
     :jurisdiction (:outage/jurisdiction o)
     :cause-category (:outage/cause-category o)
     :open? (boolean (:outage/open? o))
     :grid-outage/id (:outage/id o)
     :grid-outage/source-actor "cloud-itonami-isic-3510"
     :grid-outage/duration-minutes (:outage/duration-minutes o)}))

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
  ;; ---- additive: feeders + outage-event/restoration tracking ----
  (feeder [_ id]
    (pull->feeder (d/pull (d/db conn) feeder-pull [:feeder/id id])))
  (all-feeders [_]
    (->> (d/q '[:find [?id ...] :where [?e :feeder/id ?id]] (d/db conn))
         (map #(pull->feeder (d/pull (d/db conn) feeder-pull [:feeder/id %])))
         (sort-by :id)))
  (outage-of [_ outage-id]
    (pull->outage (d/pull (d/db conn) outage-pull [:outage/id outage-id])))
  (outage-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :outage-log/seq ?s] [?e :outage-log/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (restoration-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :restoration/seq ?s] [?e :restoration/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-outage-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :outage-sequence/jurisdiction ?j] [?e :outage-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-restoration-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :restoration-sequence/jurisdiction ?j] [?e :restoration-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (feeder-has-open-outage? [_ feeder-id]
    (boolean (d/q '[:find ?e . :in $ ?fid
                    :where [?e :outage/feeder-id ?fid] [?e :outage/open? true]]
                  (d/db conn) feeder-id)))
  (outage-open? [s outage-id]
    (boolean (:open? (outage-of s outage-id))))
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

      ;; ---- additive: feeder/outage-event tracking ----
      :feeder/upsert
      (d/transact! conn [(feeder->tx value)])

      :feeder/mark-outage-logged
      (let [feeder-id (first path)
            outage-id (:outage-id value)
            cause-category (:cause-category value)
            {:keys [result outage-record]} (log-outage-event! s feeder-id outage-id cause-category)
            jurisdiction (:jurisdiction (feeder s feeder-id))
            next-n (inc (next-outage-sequence s jurisdiction))]
        (d/transact! conn
                     [(outage->tx outage-record)
                      {:outage-sequence/jurisdiction jurisdiction :outage-sequence/next next-n}
                      {:outage-log/seq (count (outage-history s)) :outage-log/record (enc (get result "record"))}])
        result)

      :feeder/mark-restored
      (let [outage-id (first path)
            duration-minutes (:duration-minutes value)
            {:keys [result outage-patch]} (report-restoration! s outage-id duration-minutes)
            jurisdiction (:jurisdiction (outage-of s outage-id))
            next-n (inc (next-restoration-sequence s jurisdiction))]
        (d/transact! conn
                     [(outage->tx (merge (outage-of s outage-id) outage-patch {:id outage-id}))
                      {:restoration-sequence/jurisdiction jurisdiction :restoration-sequence/next next-n}
                      {:restoration/seq (count (restoration-history s)) :restoration/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-meters [s meters]
    (when (seq meters) (d/transact! conn (mapv meter->tx (vals meters)))) s)
  (with-feeders [s feeders]
    (when (seq feeders) (d/transact! conn (mapv feeder->tx (vals feeders)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:meters .. :feeders ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [meters feeders]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-meters s meters)
     (with-feeders s feeders))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo meter+feeder set -- the
  Datomic-backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
