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

;; ───────────── Additive: feeder outage-event logging + restoration reporting ─────────────

(deftest outage-event-and-restoration-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits an outage-event log or a restoration report -- the SAME posture :actuation/disconnect-service has"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :actuation/log-outage-event))
          (str "phase " n " must not auto-commit :actuation/log-outage-event"))
      (is (not (contains? auto :actuation/report-restoration))
          (str "phase " n " must not auto-commit :actuation/report-restoration")))))

(deftest feeder-log-status-and-supply-report-status-never-auto-at-any-phase
  (testing "routine, non-actuation, but still never auto-eligible in this V1 -- the SAME posture :identity/verify/:dispute/screen have"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :feeder/log-status))
          (str "phase " n " must not auto-commit :feeder/log-status"))
      (is (not (contains? auto :supply/report-status))
          (str "phase " n " must not auto-commit :supply/report-status")))))

(deftest phase-3-auto-set-is-unchanged-by-the-additive-ops
  (testing "adding the feeder outage-event pair to write-ops/phase-3 :writes does NOT expand phase 3's :auto set"
    (is (= #{:meter/intake :actuation/provision-service} (:auto (get phase/phases 3))))))

(deftest outage-ops-disabled-before-phase-3
  (testing "the feeder outage-event pair, like the meter actuation pair, is only writable from phase 3 onward"
    (is (not (contains? (:writes (get phase/phases 1)) :actuation/log-outage-event)))
    (is (not (contains? (:writes (get phase/phases 2)) :actuation/log-outage-event)))
    (is (contains? (:writes (get phase/phases 3)) :actuation/log-outage-event))
    (is (contains? (:writes (get phase/phases 3)) :actuation/report-restoration))))

(deftest feeder-log-status-enabled-from-phase-1
  (is (contains? (:writes (get phase/phases 1)) :feeder/log-status))
  (is (contains? (:writes (get phase/phases 2)) :feeder/log-status)))

(deftest gate-escalates-a-clean-outage-event-log
  (is (= :escalate (:disposition (phase/gate 3 {:op :actuation/log-outage-event} :commit)))))

(deftest gate-holds-outage-event-log-before-phase-3
  (is (= :hold (:disposition (phase/gate 1 {:op :actuation/log-outage-event} :commit))))
  (is (= :phase-disabled (:reason (phase/gate 1 {:op :actuation/log-outage-event} :commit)))))

;; ───────────── Additive: feeder <-> generator power-supply linkage ─────────────

(deftest register-power-supply-enabled-from-phase-1
  (testing "the same early-enabled posture as :feeder/log-status"
    (is (contains? (:writes (get phase/phases 1)) :feeder/register-power-supply))
    (is (contains? (:writes (get phase/phases 2)) :feeder/register-power-supply))
    (is (contains? (:writes (get phase/phases 3)) :feeder/register-power-supply))))

(deftest register-power-supply-never-auto-at-any-phase
  (testing "a directory fact about an already-agreed arrangement, but still never auto-eligible in this V1 -- the SAME posture :feeder/log-status has"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :feeder/register-power-supply))
          (str "phase " n " must not auto-commit :feeder/register-power-supply")))))

(deftest phase-3-auto-set-is-unchanged-by-power-supply-addition
  (testing "adding :feeder/register-power-supply to write-ops/phase-writes does NOT expand phase 3's :auto set"
    (is (= #{:meter/intake :actuation/provision-service} (:auto (get phase/phases 3))))))

(deftest gate-escalates-a-clean-power-supply-registration
  (is (= :escalate (:disposition (phase/gate 3 {:op :feeder/register-power-supply} :commit)))))

(deftest gate-holds-power-supply-registration-before-phase-1
  (is (= :hold (:disposition (phase/gate 0 {:op :feeder/register-power-supply} :commit))))
  (is (= :phase-disabled (:reason (phase/gate 0 {:op :feeder/register-power-supply} :commit)))))
