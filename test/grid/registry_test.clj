(ns grid.registry-test
  (:require [clojure.test :refer [deftest is]]
            [grid.registry :as r]))

;; ----------------------------- meter-number-invalid-format? -----------------------------

(deftest valid-when-well-formed
  (is (not (r/meter-number-invalid-format? {:meter-number "10234567"})))
  (is (not (r/meter-number-invalid-format? {:meter-number "123456789012"}))))

(deftest invalid-when-containing-letters-or-wrong-length
  (is (r/meter-number-invalid-format? {:meter-number "12AB567"}) "letters not allowed")
  (is (r/meter-number-invalid-format? {:meter-number "1234567"}) "too short (7 digits)")
  (is (r/meter-number-invalid-format? {:meter-number "1234567890123"}) "too long (13 digits)"))

(deftest invalid-is-true-on-missing-field
  (is (r/meter-number-invalid-format? {}))
  (is (r/meter-number-invalid-format? {:meter-number nil})))

;; ----------------------------- capacity-over-threshold? -----------------------------

(deftest over-threshold-when-strictly-greater
  (is (r/capacity-over-threshold? {:capacity-kw 51}))
  (is (not (r/capacity-over-threshold? {:capacity-kw 50})) "boundary is inclusive of the threshold itself -- not a violation")
  (is (not (r/capacity-over-threshold? {:capacity-kw 10}))))

(deftest over-threshold-respects-an-explicit-threshold
  (is (r/capacity-over-threshold? {:capacity-kw 11} 10))
  (is (not (r/capacity-over-threshold? {:capacity-kw 9} 10))))

(deftest over-threshold-is-false-on-missing-or-non-numeric-capacity
  (is (not (r/capacity-over-threshold? {})))
  (is (not (r/capacity-over-threshold? {:capacity-kw nil}))))

;; ----------------------------- register-service-provisioning -----------------------------

(deftest provisioning-is-a-draft-not-a-real-provisioning
  (let [result (r/register-service-provisioning "meter-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest provisioning-assigns-provisioning-number
  (let [result (r/register-service-provisioning "meter-1" "JPN" 7)]
    (is (= (get result "provisioning_number") "JPN-PRV-000007"))
    (is (= (get-in result ["record" "meter_id"]) "meter-1"))
    (is (= (get-in result ["record" "kind"]) "service-provisioning-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest provisioning-validation-rules
  (is (thrown? Exception (r/register-service-provisioning "" "JPN" 0)))
  (is (thrown? Exception (r/register-service-provisioning "meter-1" "" 0)))
  (is (thrown? Exception (r/register-service-provisioning "meter-1" "JPN" -1))))

;; ----------------------------- register-service-disconnection -----------------------------

(deftest disconnection-is-a-draft-not-a-real-disconnection
  (let [result (r/register-service-disconnection "meter-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest disconnection-assigns-disconnection-number
  (let [result (r/register-service-disconnection "meter-1" "JPN" 3)]
    (is (= (get result "disconnection_number") "JPN-DSC-000003"))
    (is (= (get-in result ["record" "meter_id"]) "meter-1"))
    (is (= (get-in result ["record" "kind"]) "service-disconnection-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest disconnection-validation-rules
  (is (thrown? Exception (r/register-service-disconnection "" "JPN" 0)))
  (is (thrown? Exception (r/register-service-disconnection "meter-1" "" 0)))
  (is (thrown? Exception (r/register-service-disconnection "meter-1" "JPN" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-service-provisioning "meter-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-service-provisioning "meter-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-PRV-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-PRV-000001" (get-in hist2 [1 "record_id"])))))
