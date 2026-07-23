(ns grid.facts
  "Per-jurisdiction electricity-distribution regulatory catalog -- the
  G2-style spec-basis table the Grid Transmission Governor checks every
  identity/verify (and actuation) proposal against ('did the advisor
  cite an OFFICIAL public source for this jurisdiction's electricity-
  distribution/customer-service authority, or did it invent one?').

  This is the ELECTRIC-DISTRIBUTION-utility analog of `satcom.facts`
  (`cloud-itonami-isic-6130`, satellite telecom) and `water.facts`
  (`cloud-itonami-isic-3600`, this fleet's other infrastructure/utility
  vertical, the closest DOMAIN analog): the SAME per-jurisdiction
  spec-basis discipline, citing each jurisdiction's official electricity-
  business/distribution-service regulator rather than a
  telecommunications or drinking-water authority.

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official electricity-
  distribution regulator (see `:provenance`); they are a STARTING
  catalog, not a from-scratch survey of all ~194 jurisdictions.
  Extending coverage is additive: add one map to `catalog`, cite a real
  source, done -- never invent a jurisdiction's requirements to make
  coverage look bigger.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  customer-identity-verification-record/meter-registration-record/
  interconnection-capacity-review-record/service-disconnection-log
  evidence set submitted in some form; `:legal-basis` / `:owner-
  authority` / `:provenance` are the G2 citation the governor requires
  before any `:identity/verify` (or actuation) proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "経済産業省 資源エネルギー庁 (ANRE, Agency for Natural Resources and Energy, METI)"
          :legal-basis "電気事業法 (Electricity Business Act)"
          :national-spec "一般送配電事業者の託送供給・需要家保護（生命維持装置使用者等の供給停止制限を含む）に関する規律"
          :provenance "https://www.enecho.meti.go.jp/category/electricity_and_gas/electric/"
          :required-evidence ["顧客確認記録 (customer-identity-verification-record)"
                              "計量器登録記録 (meter-registration-record)"
                              "接続容量審査記録 (interconnection-capacity-review-record)"
                              "供給停止台帳 (service-disconnection-log)"]}
   "USA" {:name "United States"
          :owner-authority "Federal Energy Regulatory Commission (FERC)"
          :legal-basis "Federal Power Act (16 U.S.C. §791a et seq.)"
          :national-spec "FERC transmission/distribution interconnection, metering and customer-protection (including medical-baseline/life-support disconnection limits) filing requirements"
          :provenance "https://www.ferc.gov/electric"
          :required-evidence ["Customer-identity-verification record"
                              "Meter-registration record"
                              "Interconnection-capacity-review record"
                              "Service-disconnection log"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Office of Gas and Electricity Markets (Ofgem)"
          :legal-basis "Electricity Act 1989"
          :national-spec "Distribution licence conditions on customer metering, connection and disconnection (including the Priority Services Register for vulnerable/life-support customers)"
          :provenance "https://www.ofgem.gov.uk/electricity"
          :required-evidence ["Customer-identity-verification record"
                              "Meter-registration record"
                              "Interconnection-capacity-review record"
                              "Service-disconnection log"]}
   "DEU" {:name "Germany"
          :owner-authority "Bundesnetzagentur"
          :legal-basis "Energiewirtschaftsgesetz (EnWG, Energy Industry Act)"
          :national-spec "Anschluss-, Mess- und Unterbrechungspflichten der Verteilnetzbetreiber (Netzanschlussverordnungen)"
          :provenance "https://www.bundesnetzagentur.de/DE/Fachthemen/ElektrizitaetundGas/start.html"
          :required-evidence ["Kundenidentitätsprüfungsnachweis (customer-identity-verification-record)"
                              "Zählerregistrierungsnachweis (meter-registration-record)"
                              "Anschlusskapazitätsprüfungsnachweis (interconnection-capacity-review-record)"
                              "Versorgungsunterbrechungsprotokoll (service-disconnection-log)"]}
   "MEX" {:name "Mexico"
          :owner-authority "Comisión Reguladora de Energía (CRE)"
          :legal-basis "Ley de la Industria Eléctrica (LIE), Diario Oficial de la Federación 11-08-2014, última reforma DOF 01-04-2024 -- Arts. 27, 33, 37 y 41"
          :national-spec "Condiciones generales que emite la CRE para el Servicio Público de Transmisión y Distribución de Energía Eléctrica: acceso no indebidamente discriminatorio a la conexión de Centros de Carga cuando sea técnicamente factible (Art. 33); medición sujeta a dichas condiciones generales (Art. 37); y una lista TASADA de causales bajo las cuales el Distribuidor puede suspender/cortar el servicio a un Usuario Final -- caso fortuito o fuerza mayor, mantenimiento programado con aviso previo, incumplimiento de pago oportuno, terminación del contrato de suministro, alteración de instrumentos de medición o control, incumplimiento de normas oficiales mexicanas o fallas en instalaciones del usuario, uso indebido de la energía, e incumplimiento contractual pactado como causal de suspensión (Art. 41); las condiciones generales del Art. 27 deben además fijar tarifas, condiciones crediticias y de suspensión del servicio, y el procedimiento de atención de quejas"
          :provenance "https://www.diputados.gob.mx/LeyesBiblio/pdf/LIElec.pdf"
          :scope-note "Unlike JPN/USA/GBR/DEU, `Distribuidor` in Mexico is BY STATUTORY DEFINITION a State-owned entity or subsidiary (LIE Art. 3 fracción XXI: \"organismos o empresas del Estado o sus subsidiarias\" -- i.e. CFE Distribución) -- the CRE does not license/permit private distribution-utility entrants the way this catalog's other 4 jurisdictions do. CRE-issued permits under LIE Art. 46 apply to Generadores and Suministradores (suppliers), NOT to Distribuidores (verified directly against CRE's own public FAQ, 'Preguntas frecuentes sobre la nueva regulación en temas eléctricos', section '¿Quién requiere permiso de la CRE?'). CRE's regulatory role over distribution here is tariff/service-condition/metering/suspension oversight of a state monopoly, not multi-entrant licensing -- cited honestly as such, never inflated to match the other 4 entries' private-licensing shape."
          :required-evidence ["registro de verificación de identidad del usuario (customer-identity-verification-record)"
                              "registro de identificación del medidor (meter-registration-record)"
                              "registro de revisión de capacidad de interconexión (interconnection-capacity-review-record)"
                              "bitácora de suspensión del servicio (service-disconnection-log)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to provision
  service or disconnect a meter on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-3510 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `grid.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))

;; ───────────── Outage-event / restoration reporting (additive) ─────────────
;;
;; A SEPARATE, smaller per-jurisdiction citation table for feeder/
;; substation outage-event logging and restoration reporting -- a
;; genuinely different regulatory hook than `catalog` above's customer-
;; identity/meter-registration/interconnection/disconnection evidence
;; set (a feeder is network infrastructure, not a customer meter --
;; there is no "customer identity" to verify for an outage event). Same
;; honest-coverage discipline as `catalog`: a jurisdiction absent from
;; `outage-catalog` has NO spec-basis for outage-event reporting, full
;; stop, even if it DOES have an entry in `catalog` above (e.g. "DEU"
;; is covered for meter provisioning/disconnection but deliberately
;; has no verified outage-reporting-specific citation here -- never
;; fabricated to make coverage look bigger).

(def outage-catalog
  "iso3 -> outage-event/restoration-reporting requirement map."
  {"JPN" {:owner-authority "経済産業省 資源エネルギー庁 (ANRE, Agency for Natural Resources and Energy, METI)"
          :legal-basis "電気事業法 (Electricity Business Act, Act No. 170 of 1964) 第2条第1項第8号"
          :national-spec "一般送配電事業者の託送供給義務の履行状況としての、供給区域内outage事象の記録"
          :provenance "https://www.enecho.meti.go.jp/category/electricity_and_gas/electric/"
          :required-evidence ["outage-event-log"]}
   "USA" {:owner-authority "North American Electric Reliability Corporation (NERC), under FERC oversight"
          :legal-basis "NERC Reliability Standard EOP-004 (Event Reporting)"
          :national-spec "Bulk Electric System event reporting to the Electric Reliability Organization within 24 hours of recognition of an event-type threshold, or by the Responsible Entity's next business day"
          :provenance "https://www.nerc.com/pa/stand/reliability%20standards/eop-004-4.pdf"
          :required-evidence ["eop-004-event-report"]
          :scope-note "Federal Bulk-Electric-System-level event reporting only -- distribution-level (non-BES) outage-reporting requirements are State Public Utility Commission-regulated and honestly OUT OF SCOPE for this catalog."}
   "GBR" {:owner-authority "Office of Gas and Electricity Markets (Ofgem)"
          :legal-basis "The Electricity (Standards of Performance) Regulations 2015 (SI 2015/699)"
          :national-spec "Distribution supply-restoration time targets: 12h under normal weather, up to 24h where high-voltage fault incidence reaches at least 8x the normal rate within a rolling 24h period (the lightning-event threshold), up to 48h under severe/exceptional weather"
          :provenance "https://www.legislation.gov.uk/uksi/2015/699/body"
          :required-evidence ["restoration-time-log"]}})

(defn outage-spec-basis
  "The jurisdiction's OUTAGE-EVENT-REPORTING requirement map, or nil --
  nil means NO spec-basis for outage-event logging/restoration
  reporting specifically, even if `spec-basis` above has an entry for
  the same iso3 (a DIFFERENT catalog, see ns docstring)."
  [iso3]
  (get outage-catalog iso3))

(defn outage-evidence-satisfied?
  "Does `submitted` satisfy every outage-event-reporting evidence item
  listed for `iso3` in `outage-catalog`? Missing outage spec-basis ->
  never satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (outage-spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))
