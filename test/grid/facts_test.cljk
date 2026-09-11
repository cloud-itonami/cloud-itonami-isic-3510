(ns grid.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [grid.facts :as facts]))

(deftest jpn-has-a-spec-basis
  (is (some? (facts/spec-basis "JPN")))
  (is (string? (:provenance (facts/spec-basis "JPN")))))

(deftest mex-has-a-spec-basis
  (is (some? (facts/spec-basis "MEX")))
  (is (string? (:provenance (facts/spec-basis "MEX"))))
  (is (= 4 (count (facts/evidence-checklist "MEX")))))

(deftest unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "ATL"))))

(deftest coverage-never-reports-a-missing-jurisdiction-as-covered
  (let [report (facts/coverage ["JPN" "ATL" "GBR"])]
    (is (= 2 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["GBR" "JPN"] (:covered-jurisdictions report)))))

(deftest required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "JPN")]
    (is (facts/required-evidence-satisfied? "JPN" all))
    (is (not (facts/required-evidence-satisfied? "JPN" (rest all))))
    (is (not (facts/required-evidence-satisfied? "ATL" all)) "no spec-basis -> never satisfied")))

;; ───────────── Outage-event / restoration reporting (additive) ─────────────

(deftest outage-catalog-jpn-usa-gbr-have-a-spec-basis
  (is (some? (facts/outage-spec-basis "JPN")))
  (is (some? (facts/outage-spec-basis "USA")))
  (is (some? (facts/outage-spec-basis "GBR")))
  (is (every? string? (map :provenance (map facts/outage-spec-basis ["JPN" "USA" "GBR"])))))

(deftest outage-catalog-deu-is-honestly-out-of-scope
  (testing "DEU has a spec-basis in the MAIN catalog but deliberately NOT in outage-catalog -- never fabricated"
    (is (some? (facts/spec-basis "DEU")))
    (is (nil? (facts/outage-spec-basis "DEU")))))

(deftest outage-catalog-unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/outage-spec-basis "ATL"))))

(deftest outage-evidence-satisfied-needs-every-item
  (let [jpn-required (:required-evidence (facts/outage-spec-basis "JPN"))]
    (is (facts/outage-evidence-satisfied? "JPN" jpn-required))
    (is (not (facts/outage-evidence-satisfied? "JPN" [])))
    (is (not (facts/outage-evidence-satisfied? "ATL" jpn-required)) "no outage spec-basis -> never satisfied")))
