(ns grid.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see `cloud-itonami-isic-6130`'s
  `satcom.store-contract-test` for the same pattern on a sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [grid.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Sakura Ryokan" (:customer-name (store/meter s "meter-1"))))
      (is (= "JPN" (:jurisdiction (store/meter s "meter-1"))))
      (is (= "10234567" (:meter-number (store/meter s "meter-1"))))
      (is (false? (:protected-recipient? (store/meter s "meter-1"))))
      (is (false? (:billing-dispute-unresolved? (store/meter s "meter-1"))))
      (is (= "12AB567" (:meter-number (store/meter s "meter-3"))))
      (is (true? (:protected-recipient? (store/meter s "meter-4"))))
      (is (= 250 (:capacity-kw (store/meter s "meter-5"))))
      (is (true? (:billing-dispute-unresolved? (store/meter s "meter-6"))))
      (is (false? (:service-provisioned? (store/meter s "meter-1"))))
      (is (false? (:service-disconnected? (store/meter s "meter-1"))))
      (is (= ["meter-1" "meter-2" "meter-3" "meter-4" "meter-5" "meter-6"]
             (mapv :id (store/all-meters s))))
      (is (nil? (store/dispute-screen-of s "meter-1")))
      (is (nil? (store/identity-verification-of s "meter-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/provisioning-history s)))
      (is (= [] (store/disconnection-history s)))
      (is (zero? (store/next-provisioning-sequence s "JPN")))
      (is (zero? (store/next-disconnection-sequence s "JPN")))
      (is (false? (store/meter-already-provisioned? s "meter-1")))
      (is (false? (store/meter-already-disconnected? s "meter-1"))))))

(deftest feeder-read-parity
  (testing "additive: feeders, a SEPARATE entity from meters"
    (doseq [[label s] (backends)]
      (testing label
        (is (= "SS-01" (:substation-id (store/feeder s "feeder-1"))))
        (is (= "JPN" (:jurisdiction (store/feeder s "feeder-1"))))
        (is (= "ATL" (:jurisdiction (store/feeder s "feeder-3"))))
        (is (= ["feeder-1" "feeder-2" "feeder-3"] (mapv :id (store/all-feeders s))))
        (is (nil? (store/outage-of s "outage-nonexistent")))
        (is (= [] (store/outage-history s)))
        (is (= [] (store/restoration-history s)))
        (is (zero? (store/next-outage-sequence s "JPN")))
        (is (zero? (store/next-restoration-sequence s "JPN")))
        (is (false? (store/feeder-has-open-outage? s "feeder-1")))
        (is (false? (store/outage-open? s "outage-nonexistent")))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :meter/upsert
                                 :value {:id "meter-1" :customer-name "Sakura Ryokan"}})
        (is (= "Sakura Ryokan" (:customer-name (store/meter s "meter-1"))))
        (is (= "10234567" (:meter-number (store/meter s "meter-1"))) "unrelated field preserved"))
      (testing "verification / dispute-screen payloads commit and read back"
        (store/commit-record! s {:effect :verification/set :path ["meter-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/identity-verification-of s "meter-1")))
        (store/commit-record! s {:effect :dispute-screen/set :path ["meter-1"]
                                 :payload {:meter-id "meter-1" :verdict :resolved}})
        (is (= {:meter-id "meter-1" :verdict :resolved} (store/dispute-screen-of s "meter-1"))))
      (testing "service provisioning drafts a record and advances the sequence"
        (store/commit-record! s {:effect :meter/mark-provisioned :path ["meter-1"]})
        (is (= "JPN-PRV-000000" (get (first (store/provisioning-history s)) "record_id")))
        (is (= "service-provisioning-draft" (get (first (store/provisioning-history s)) "kind")))
        (is (true? (:service-provisioned? (store/meter s "meter-1"))))
        (is (= 1 (count (store/provisioning-history s))))
        (is (= 1 (store/next-provisioning-sequence s "JPN")))
        (is (true? (store/meter-already-provisioned? s "meter-1")))
        (is (false? (store/meter-already-provisioned? s "meter-2"))))
      (testing "service disconnection drafts a record and advances the sequence"
        (store/commit-record! s {:effect :meter/mark-disconnected :path ["meter-1"]})
        (is (= "JPN-DSC-000000" (get (first (store/disconnection-history s)) "record_id")))
        (is (= "service-disconnection-draft" (get (first (store/disconnection-history s)) "kind")))
        (is (true? (:service-disconnected? (store/meter s "meter-1"))))
        (is (= 1 (count (store/disconnection-history s))))
        (is (= 1 (store/next-disconnection-sequence s "JPN")))
        (is (true? (store/meter-already-disconnected? s "meter-1")))
        (is (false? (store/meter-already-disconnected? s "meter-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest feeder-outage-write-and-cross-actor-shape-parity
  (testing "additive: outage-event logging opens the guard, restoration closes it, on BOTH backends"
    (doseq [[label s] (backends)]
      (testing label
        (testing "feeder upsert merges, preserving untouched fields"
          (store/commit-record! s {:effect :feeder/upsert
                                   :value {:id "feeder-1" :status :maintenance}})
          (is (= :maintenance (:status (store/feeder s "feeder-1"))))
          (is (= "SS-01" (:substation-id (store/feeder s "feeder-1"))) "unrelated field preserved"))
        (testing "outage-event logging opens the guard and drafts a record"
          (store/commit-record! s {:effect :feeder/mark-outage-logged :path ["feeder-1"]
                                   :value {:outage-id "outage-1" :cause-category :cause/equipment-failure}})
          (is (= "JPN-OUT-000000" (get (first (store/outage-history s)) "record_id")))
          (is (= "outage-event-draft" (get (first (store/outage-history s)) "kind")))
          (is (true? (store/feeder-has-open-outage? s "feeder-1")))
          (is (true? (store/outage-open? s "outage-1")))
          (is (= "feeder-1" (:feeder-id (store/outage-of s "outage-1"))))
          (is (= 1 (count (store/outage-history s))))
          (is (= 1 (store/next-outage-sequence s "JPN"))))
        (testing "the committed outage-event record carries the isic-3510 -> jsic-4721 cross-actor fields"
          (let [o (store/outage-of s "outage-1")]
            (is (= "outage-1" (:grid-outage/id o)))
            (is (= "cloud-itonami-isic-3510" (:grid-outage/source-actor o)))
            (is (nil? (:grid-outage/duration-minutes o)) "not yet restored")))
        (testing "a second outage-event on the SAME feeder is a caller's job to guard against (governor-layer, not store-layer) -- the store itself just tracks the LATEST open outage per feeder"
          (is (true? (store/feeder-has-open-outage? s "feeder-1"))))
        (testing "restoration reporting closes the guard and drafts a record"
          (store/commit-record! s {:effect :feeder/mark-restored :path ["outage-1"]
                                   :value {:duration-minutes 75}})
          (is (= "JPN-RST-000000" (get (first (store/restoration-history s)) "record_id")))
          (is (= "outage-restoration-draft" (get (first (store/restoration-history s)) "kind")))
          (is (false? (store/feeder-has-open-outage? s "feeder-1")))
          (is (false? (store/outage-open? s "outage-1")))
          (is (= 75 (:grid-outage/duration-minutes (store/outage-of s "outage-1"))))
          (is (= 1 (count (store/restoration-history s))))
          (is (= 1 (store/next-restoration-sequence s "JPN"))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/meter s "nope")))
    (is (= [] (store/all-meters s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/provisioning-history s)))
    (is (= [] (store/disconnection-history s)))
    (is (zero? (store/next-provisioning-sequence s "JPN")))
    (is (zero? (store/next-disconnection-sequence s "JPN")))
    (store/with-meters s {"x" {:id "x" :customer-name "n" :meter-number "10234567"
                               :capacity-kw 10
                               :protected-recipient? false
                               :billing-dispute-unresolved? false
                               :service-provisioned? false :service-disconnected? false
                               :jurisdiction "JPN" :status :intake}})
    (is (= "n" (:customer-name (store/meter s "x"))))))
