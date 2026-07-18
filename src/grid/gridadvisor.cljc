(ns grid.gridadvisor
  "Grid Distribution Advisor client -- the *contained intelligence node*
  for the electric-distribution-utility actor.

  It normalizes meter-intake, drafts a per-jurisdiction customer-
  identity + electricity-distribution evidence checklist, screens
  meters for an unresolved billing/service dispute, drafts the
  service-provisioning action, and drafts the service-disconnection
  action. CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a
  committed record or a real service provisioning/disconnection. Every
  output is censored downstream by `grid.governor` before anything
  touches the SSoT, and `:actuation/disconnect-service` proposals NEVER
  auto-commit at any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/provision-service | :actuation/disconnect-service | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [grid.facts :as facts]
            [grid.registry :as registry]
            [grid.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the meter, meter-number figures or jurisdiction. High
  confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "計量器記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :meter/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- verify-identity
  "Per-jurisdiction customer-identity + electricity-distribution
  evidence checklist draft. `:no-spec?` injects the failure mode we
  must defend against: proposing a checklist for a jurisdiction with NO
  official spec-basis in `grid.facts` -- the Grid Transmission Governor
  must reject this (never invent a jurisdiction's requirements).

  Additive: `subject` may ALSO be a feeder-id (`grid.store/feeder`,
  tried when `subject` does not resolve as a meter) -- in that case the
  checklist is drafted against the SEPARATE `grid.facts/outage-
  catalog`, not `grid.facts/catalog`. This lets `:actuation/log-
  outage-event`'s own evidence-incomplete gate (`grid.governor`) reuse
  the SAME `:identity/verify` op + `grid.store/identity-verification-
  of` mechanism meters use, rather than inventing a second verify op --
  a feeder-id and a meter-id never collide (see `grid.store` ns
  docstring), so this is unambiguous. Behaviour for an actual meter-id
  subject is UNCHANGED."
  [db {:keys [subject no-spec?]}]
  (let [m (store/meter db subject)
        f (when-not m (store/feeder db subject))
        outage? (some? f)
        iso3 (if no-spec? "ATL" (:jurisdiction (or m f)))
        sb (if outage? (facts/outage-spec-basis iso3) (facts/spec-basis iso3))]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "grid.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :verification/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :verification/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-dispute
  "Billing/service dispute screening draft. `:billing-dispute-
  unresolved?` on the meter record injects the failure mode: the Grid
  Transmission Governor must HOLD, un-overridably, on any unresolved
  dispute."
  [db {:keys [subject]}]
  (let [m (store/meter db subject)]
    (cond
      (nil? m)
      {:summary "対象計量器記録が見つかりません" :rationale "no meter record"
       :cites [] :effect :dispute-screen/set :value {:meter-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (true? (:billing-dispute-unresolved? m))
      {:summary    (str (:customer-name m) ": 未解決の請求・供給紛争を検出")
       :rationale  "スクリーニングが未解決の請求・供給紛争を検出。人手確認とホールドが必須。"
       :cites      [:dispute-check]
       :effect     :dispute-screen/set
       :value      {:meter-id subject :verdict :unresolved}
       :stake      nil
       :confidence 0.95}

      :else
      {:summary    (str (:customer-name m) ": 未解決の請求・供給紛争なし")
       :rationale  "請求・供給紛争スクリーニング完了。"
       :cites      [:dispute-check]
       :effect     :dispute-screen/set
       :value      {:meter-id subject :verdict :resolved}
       :stake      nil
       :confidence 0.9})))

(defn- propose-service-provisioning
  "Draft the actual SERVICE-PROVISIONING action -- energizing a real
  connection and a real meter for a customer. ALWAYS `:stake
  :actuation/provision-service`. Whether this reaches auto-commit
  depends on BOTH `grid.phase` (phase 3 membership) AND `grid.
  governor`'s independent capacity-threshold recompute -- a clean,
  under-threshold connection MAY auto-commit at phase 3; an
  over-threshold connection always escalates. See README `Actuation`
  and this repo's own ADR-0001."
  [db {:keys [subject]}]
  (let [m (store/meter db subject)]
    {:summary    (str subject " 向け電力供給開始提案"
                      (when m (str " (customer=" (:customer-name m) ")")))
     :rationale  (if m
                   (str "meter-number=" (:meter-number m) " capacity-kw=" (:capacity-kw m))
                   "計量器記録が見つかりません")
     :cites      (if m [subject] [])
     :effect     :meter/mark-provisioned
     :value      {:meter-id subject}
     :stake      :actuation/provision-service
     :confidence (if (and m (not (registry/meter-number-invalid-format? m))) 0.9 0.3)}))

(defn- propose-service-disconnection
  "Draft the actual SERVICE-DISCONNECTION action -- disconnecting a real
  customer's electricity supply. ALWAYS `:stake :actuation/disconnect-
  service` -- this is a REAL-WORLD act (and, like `satcom.
  satcomadvisor`'s (`cloud-itonami-isic-6130`) own `:actuation/suspend-
  service`, a NEGATIVE one -- withholding an ongoing necessity service,
  not issuing a new record), never a draft the actor may auto-run. See
  README `Actuation`: no phase ever adds this op to a phase's `:auto`
  set (`grid.phase`); the governor also always escalates on
  `:actuation/disconnect-service` -- AND, independently, HARD-holds it
  un-overridably if the meter is a protected recipient
  (`grid.governor/protected-recipient-violations`)."
  [db {:keys [subject]}]
  (let [m (store/meter db subject)]
    {:summary    (str subject " 向け供給停止提案"
                      (when m (str " (customer=" (:customer-name m) ")")))
     :rationale  (if m
                   "billing-and-service-dispute-checklist referenced"
                   "計量器記録が見つかりません")
     :cites      (if m [subject] [])
     :effect     :meter/mark-disconnected
     :value      {:meter-id subject}
     :stake      :actuation/disconnect-service
     :confidence (if m 0.9 0.3)}))

;; ----------------------------- additive: feeder outage-event pair -----------------------------

(defn- log-feeder-status
  "Feeder/substation directory upsert -- the SAME low-stakes normalize-
  only shape as `normalize-intake`, applied to a feeder instead of a
  meter."
  [_db {:keys [patch]}]
  {:summary    (str "フィーダー状態記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :feeder/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- propose-outage-event
  "Draft the OUTAGE-EVENT-LOGGING action for `subject` (a feeder-id).
  ALWAYS `:stake :actuation/log-outage-event`. Looks up `grid.facts/
  outage-catalog` (a SEPARATE, smaller catalog than `grid.facts/
  catalog` -- see `grid.governor` ns docstring) via the feeder's own
  recorded `:jurisdiction`; a feeder in a jurisdiction with no outage
  spec-basis (e.g. `feeder-3`, ATL) drafts an empty-cites, low-
  confidence proposal the Grid Transmission Governor must reject --
  never invent a jurisdiction's outage-reporting requirements."
  [db {:keys [subject outage-id cause-category]}]
  (let [f (store/feeder db subject)
        sb (facts/outage-spec-basis (:jurisdiction f))]
    (if (nil? sb)
      {:summary    (str (:jurisdiction f) " の公式outage-reporting spec-basisが見つかりません")
       :rationale  "grid.facts/outage-catalog に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :feeder/mark-outage-logged
       :value      {:feeder-id subject :outage-id outage-id :cause-category cause-category :spec-basis nil}
       :stake      :actuation/log-outage-event
       :confidence 0.3}
      {:summary    (str subject " のoutageイベント記録提案 (cause=" cause-category ")")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :feeder/mark-outage-logged
       :value      {:feeder-id subject :outage-id outage-id :cause-category cause-category
                    :spec-basis (:provenance sb) :legal-basis (:legal-basis sb)}
       :stake      :actuation/log-outage-event
       :confidence 0.9})))

(defn- propose-restoration
  "Draft the OUTAGE-RESTORATION-REPORTING action for `subject` (an
  outage-id). ALWAYS `:stake :actuation/report-restoration`. Resolves
  the outage's own feeder (via `grid.store/outage-of`) to look up the
  SAME `grid.facts/outage-catalog` spec-basis `propose-outage-event`
  used to open it."
  [db {:keys [subject duration-minutes]}]
  (let [o (store/outage-of db subject)
        f (store/feeder db (:feeder-id o))
        sb (facts/outage-spec-basis (:jurisdiction f))]
    {:summary    (str subject " の復旧報告提案 (duration-minutes=" duration-minutes ")")
     :rationale  (if sb (str "公式ソース: " (:provenance sb)) "outageイベント/フィーダー記録が見つかりません")
     :cites      (if sb [(:legal-basis sb) (:provenance sb)] [])
     :effect     :feeder/mark-restored
     :value      {:outage-id subject :duration-minutes duration-minutes :spec-basis (when sb (:provenance sb))}
     :stake      :actuation/report-restoration
     :confidence (if sb 0.9 0.3)}))

(defn- report-supply-status
  "Draft a routine demand-side SUPPLY-STATUS report for `subject` (a
  feeder-id) -- NOT an actuation (`:stake nil`), the same low-stakes
  informational shape as `screen-dispute`'s clean branch."
  [db {:keys [subject]}]
  (let [f (store/feeder db subject)]
    {:summary    (str subject " の需要側供給状況報告"
                      (when f (str " (status=" (:status f) ")")))
     :rationale  (if f "フィーダー自身の記録済みstatusを報告" "フィーダー記録が見つかりません")
     :cites      (if f [subject] [])
     :effect     :supply-status/report
     :value      {:feeder-id subject :status (:status f)}
     :stake      nil
     :confidence (if f 0.9 0.3)}))

;; ----------------------------- additive: feeder <-> generator power-supply linkage -----------------------------

(defn- register-power-supply
  "Feeder power-supply-SOURCE registration -- draft the ADMINISTRATIVE
  linkage of a feeder to an upstream GENERATION actor's own committed
  `:power-supply` record: `:power-supply/id` / `:power-supply/source-
  actor` (e.g. \"cloud-itonami-isic-3511\"/\"cloud-itonami-isic-3512\")
  / `:power-supply/feeder-ref` / `:power-supply/capacity-mw` /
  `:power-supply/agreement-start-iso` -- see superproject
  ADR-2800000500 for why this shape is shared, flat and non-code (the
  SAME 'no shared library, just a field-name convention each side
  independently verifies' discipline `:grid-outage/*` (ADR-2608510000)
  and the isic-1075<->jsic-4721 `:handoff/*` records established).

  Reuses the SAME low-stakes normalize-only shape as `log-feeder-
  status` (`:effect :feeder/upsert`): registering WHICH generator
  supplies a feeder is a directory fact about an existing, already-
  agreed arrangement, not a real-time dispatch decision -- this actor
  never calls the generation actor's own code, never re-derives its
  own generation output from it, and never actuates a supply change
  because of it. The `:power-supply/*` fields are entirely OPTIONAL on
  a feeder record; a feeder with none of them behaves exactly as
  before this addition."
  [_db {:keys [patch]}]
  {:summary    (str "フィーダー電力供給元登録: " (pr-str (keys patch)))
   :rationale  "入力patchの正規化のみ。新規事実の生成なし -- 発電actor側の確定record(:power-supply/*)をフィーダー記録に紐付けるだけ。"
   :cites      (vec (keys patch))
   :effect     :feeder/upsert
   :value      patch
   :stake      nil
   :confidence 0.95})

;; ----------------------------- additive: feeder <-> downstream power-metering -----------------------------

(defn- log-metering-reading
  "Draft a POWER-METERING reading log for `subject` (a feeder-id) -- the
  shared `:power-metering/*` wire shape this actor hands to a
  downstream physical-operations client (e.g. cloud-itonami-jsic-4721)
  for reconciliation against ITS own registered equipment-assets' rated
  power draw. See superproject ADR-2800001100 for the full shared
  shape. Reuses the SAME low-stakes normalize-shape as `log-feeder-
  status`/`register-power-supply` (`:effect :feeder/upsert`): logging a
  metering reading is a routine operational-measurement fact, not a
  real-time dispatch decision or actuation -- this actor never calls
  the downstream client's own code and never actuates anything because
  of it.

  `:power-metering/id` is generated deterministically from (subject,
  period-start-iso) -- a feeder cannot have two DIFFERENT readings
  that start at the exact same instant, so this needs no jurisdiction-
  scoped sequence counter (unlike `grid.registry/register-outage-
  event`'s `-OUT-000001`-style numbering) -- a deliberate scope
  reduction, see this actor's own README/ADR. `grid.governor`'s
  `metering-reading-invalid-violations` INDEPENDENTLY re-verifies the
  feeder's own existence, the period's own chronological ordering and
  a non-negative `:consumed-kwh` before this is ever allowed to
  commit -- this fn does NOT pre-validate those (the governor is the
  fail-closed backstop, the same 'advisor proposes, governor
  independently verifies' split every other proposal in this ns
  uses)."
  [db {:keys [subject client-actor period-start-iso period-end-iso consumed-kwh]}]
  (let [f (store/feeder db subject)]
    (if (nil? f)
      {:summary    (str subject " のフィーダー記録が見つかりません")
       :rationale  "存在しないフィーダーへの計量記録は提案できない"
       :cites      []
       :effect     :feeder/upsert
       :value      {}
       :stake      nil
       :confidence 0.2}
      {:summary    (str subject " の計量記録 " period-start-iso "〜" period-end-iso
                        " (" consumed-kwh "kWh, client=" client-actor ") を提案")
       :rationale  (str "フィーダー自身の記録済み jurisdiction=" (:jurisdiction f) " に基づく計量記録")
       :cites      [subject]
       :effect     :feeder/upsert
       :value      {:id subject
                    :power-metering/id (str subject "-MTR-" period-start-iso)
                    :power-metering/feeder-ref subject
                    :power-metering/client-actor client-actor
                    :power-metering/period-start-iso period-start-iso
                    :power-metering/period-end-iso period-end-iso
                    :power-metering/consumed-kwh consumed-kwh}
       :stake      nil
       :confidence 0.95})))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :meter/intake                    (normalize-intake db request)
    :identity/verify                 (verify-identity db request)
    :dispute/screen                  (screen-dispute db request)
    :actuation/provision-service     (propose-service-provisioning db request)
    :actuation/disconnect-service    (propose-service-disconnection db request)
    :feeder/log-status               (log-feeder-status db request)
    :actuation/log-outage-event      (propose-outage-event db request)
    :actuation/report-restoration    (propose-restoration db request)
    :supply/report-status            (report-supply-status db request)
    :feeder/register-power-supply    (register-power-supply db request)
    :feeder/log-metering-reading     (log-metering-reading db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは電力配電事業者の供給開始・供給停止エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:meter/upsert|:verification/set|:dispute-screen/set|"
       ":meter/mark-provisioned|:meter/mark-disconnected) "
       ":stake(:actuation/provision-service か :actuation/disconnect-service か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :identity/verify                 {:meter (store/meter st subject)}
    :dispute/screen                  {:meter (store/meter st subject)}
    :actuation/provision-service     {:meter (store/meter st subject)}
    :actuation/disconnect-service    {:meter (store/meter st subject)}
    :feeder/log-status               {:feeder (store/feeder st subject)}
    :actuation/log-outage-event      {:feeder (store/feeder st subject)}
    :actuation/report-restoration    {:outage (store/outage-of st subject)}
    :supply/report-status            {:feeder (store/feeder st subject)}
    :feeder/register-power-supply    {:feeder (store/feeder st subject)}
    :feeder/log-metering-reading     {:feeder (store/feeder st subject)}
    {:meter (store/meter st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Grid Transmission Governor
  escalates/holds -- an LLM hiccup can never auto-provision service or
  auto-disconnect service."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :gridadvisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
