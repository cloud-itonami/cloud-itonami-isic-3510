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
  must reject this (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [m (store/meter db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction m))
        sb (facts/spec-basis iso3)]
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
