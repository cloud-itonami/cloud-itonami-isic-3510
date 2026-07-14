(ns grid.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:actuation/disconnect-service` must NEVER be a member of
  any phase's `:auto` set, while `:actuation/provision-service` IS
  auto-eligible at phase 3 (this fleet's second asymmetric dual-
  actuation phase table -- see `grid.phase` ns docstring)."
  (:require [clojure.test :refer [deftest is testing]]
            [grid.phase :as phase]))

(deftest disconnect-service-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real disconnection"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :actuation/disconnect-service))
          (str "phase " n " must not auto-commit :actuation/disconnect-service")))))

(deftest identity-verify-never-auto-at-any-phase
  (testing "verification carries no direct capital risk, but is still never auto-eligible, matching every sibling verification op in this fleet"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :identity/verify))
          (str "phase " n " must not auto-commit :identity/verify")))))

(deftest dispute-screen-never-auto-at-any-phase
  (testing "screening carries no direct capital risk, but is still never auto-eligible, matching every sibling screening op in this fleet"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :dispute/screen))
          (str "phase " n " must not auto-commit :dispute/screen")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-intake-and-provisioning-only
  (testing ":meter/intake carries no direct capital risk; :actuation/provision-service is auto-eligible IN PRINCIPLE at phase 3 but still gated per-request by the governor's capacity-threshold recompute (see grid.governor)"
    (is (= #{:meter/intake :actuation/provision-service} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :meter/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :actuation/disconnect-service} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :identity/verify} :commit)))))

(deftest gate-auto-commits-a-clean-provisioning-at-phase-3
  (is (= :commit (:disposition (phase/gate 3 {:op :actuation/provision-service} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :meter/intake} :commit)))))
