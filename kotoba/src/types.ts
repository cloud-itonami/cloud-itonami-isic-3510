/**
 * open-denki kotoba — record types.
 *
 * Per ADR-2605203000 Option B (PDS XRPC). Smart-grid topology design +
 * operations (IEC 61968/61970 CIM aligned).
 *
 * Topology hierarchy:
 *   GenerationNode (solar/wind/hydro/thermal/nuclear/storage)
 *           ┐
 *           │
 *   Substation (HV/MV/LV transformer)
 *           │
 *           ↓
 *        Feeder (substation → delivery)
 *           │
 *           ↓
 *      SmartMeter (consumption / generation / bidirectional)
 *           │
 *           ↓
 *      MeterReading (monotonic for consumption)
 *
 * Operation events:
 *   Fault, DemandResponse, RenewableOutput
 *
 * Identity hierarchy (path-based DIDs per CLAUDE.md):
 *   did:web:open-denki.etzhayyim.com:gen:{id}     — GenerationNode
 *   did:web:open-denki.etzhayyim.com:sub:{id}     — Substation
 *   did:web:open-denki.etzhayyim.com:feeder:{id}  — Feeder
 *   did:web:open-denki.etzhayyim.com:meter:{id}   — SmartMeter
 *   did:web:open-denki.etzhayyim.com:reading:{meterId}-{readingSeq}
 *   did:web:open-denki.etzhayyim.com:fault:{id}
 *   did:web:open-denki.etzhayyim.com:dr:{id}
 *   did:web:open-denki.etzhayyim.com:output:{genId}-{outputSeq}
 */

export const OPEN_DENKI_DID_PREFIX =
  "did:web:open-denki.etzhayyim.com:" as const;

export type GenerationKind =
  | "solar"
  | "wind"
  | "hydro"
  | "thermal"
  | "nuclear"
  | "storage";

export type VoltageLevel = "hv" | "mv" | "lv";

export type SubstationKind = "transmission" | "distribution" | "switching";

export type FeederStatus = "active" | "standby" | "out-of-service";

export type MeterKind = "consumption" | "generation" | "bidirectional";

// ─── Topology tier (slice 1) ────────────────────────────────────────

export interface GenerationNodeRecord {
  did: string;
  nodeId: string;
  name: string;
  kind: GenerationKind;
  /** Nameplate capacity in kW (whole number — AT Lexicon no-float). */
  capacityKw: number;
  voltageLevel?: VoltageLevel;
  operatorDid?: string;
  locationHint?: string;
  commissionedAt?: string;
  createdAt: string;
}

export interface GenerationNodeView extends GenerationNodeRecord {
  generationNodeUri: string;
}

export interface DefineGenerationNodeInput {
  nodeId: string;
  name: string;
  kind: GenerationKind;
  capacityKw: number;
  voltageLevel?: VoltageLevel;
  operatorDid?: string;
  locationHint?: string;
  commissionedAt?: string;
}

export interface DefineGenerationNodeOutput {
  status: "registered" | "alreadyExists" | "rejected";
  generationNodeUri?: string;
  did?: string;
  nodeId?: string;
  error?: string;
}

export interface SubstationRecord {
  did: string;
  substationId: string;
  name: string;
  kind: SubstationKind;
  /** Primary voltage in kV (whole number). */
  primaryVoltageKv: number;
  /** Secondary voltage in kV (whole number). */
  secondaryVoltageKv: number;
  /** Capacity in MVA (whole number). */
  capacityMva: number;
  operatorDid?: string;
  locationHint?: string;
  commissionedAt?: string;
  createdAt: string;
}

export interface SubstationView extends SubstationRecord {
  substationUri: string;
}

export interface DefineSubstationInput {
  substationId: string;
  name: string;
  kind: SubstationKind;
  primaryVoltageKv: number;
  secondaryVoltageKv: number;
  capacityMva: number;
  operatorDid?: string;
  locationHint?: string;
  commissionedAt?: string;
}

export interface DefineSubstationOutput {
  status: "registered" | "alreadyExists" | "rejected";
  substationUri?: string;
  did?: string;
  substationId?: string;
  error?: string;
}

export interface FeederRecord {
  did: string;
  feederId: string;
  name: string;
  substationDid: string;
  voltageLevel: VoltageLevel;
  /** Customer count served (whole number). */
  customerCount: number;
  status: FeederStatus;
  /** Length in meters (whole number). */
  lengthM?: number;
  createdAt: string;
}

export interface FeederView extends FeederRecord {
  feederUri: string;
}

export interface DefineFeederInput {
  feederId: string;
  name: string;
  substationId: string;
  voltageLevel: VoltageLevel;
  customerCount: number;
  status?: FeederStatus;
  lengthM?: number;
}

export interface DefineFeederOutput {
  status: "registered" | "alreadyExists" | "rejected" | "substationNotFound";
  feederUri?: string;
  did?: string;
  feederId?: string;
  error?: string;
}

export interface SmartMeterRecord {
  did: string;
  meterId: string;
  /** SAID / GIS identifier. */
  serialNumber?: string;
  feederDid?: string;
  kind: MeterKind;
  ownerDid?: string;
  installedAt?: string;
  createdAt: string;
}

export interface SmartMeterView extends SmartMeterRecord {
  smartMeterUri: string;
}

export interface RegisterSmartMeterInput {
  meterId: string;
  serialNumber?: string;
  feederId?: string;
  kind: MeterKind;
  ownerDid?: string;
  installedAt?: string;
}

export interface RegisterSmartMeterOutput {
  status: "registered" | "alreadyExists" | "rejected" | "feederNotFound";
  smartMeterUri?: string;
  did?: string;
  meterId?: string;
  error?: string;
}

export type AnyNodeView =
  | (GenerationNodeView & { _kind: "generation" })
  | (SubstationView & { _kind: "substation" });

export interface GetNodeInput {
  /** Either a generation node ID or substation ID. */
  nodeId?: string;
  /** Disambiguator. */
  kind?: "generation" | "substation";
}

export interface GetNodeOutput {
  node?: AnyNodeView;
  error?: string;
}

// ─── Operation events tier (slice 2) ────────────────────────────────

export interface MeterReadingRecord {
  did: string;
  meterDid: string;
  meterId: string;
  readingSeq: number;
  /** Cumulative energy in kWh (integer — Wh / 1000). */
  kwhCumulative: number;
  /** Instantaneous demand in kW. */
  kwDemand?: number;
  /** Voltage in volts. */
  voltageV?: number;
  readAt: string;
  createdAt: string;
}

export interface MeterReadingView extends MeterReadingRecord {
  readingUri: string;
}

export interface RecordMeterReadingInput {
  meterId: string;
  readingSeq: number;
  kwhCumulative: number;
  kwDemand?: number;
  voltageV?: number;
  readAt: string;
}

export interface RecordMeterReadingOutput {
  status:
    | "recorded"
    | "alreadyExists"
    | "rejected"
    | "nonMonotonic";
  readingUri?: string;
  did?: string;
  meterId?: string;
  readingSeq?: number;
  error?: string;
}

export type FaultKind =
  | "earth_fault"
  | "short_circuit"
  | "outage"
  | "voltage_deviation"
  | "overload";

export type FaultSeverity = "critical" | "major" | "moderate" | "minor";

export interface FaultRecord {
  did: string;
  faultId: string;
  faultKind: FaultKind;
  feederDid: string;
  severity: FaultSeverity;
  publicNotice: boolean;
  affectedCustomers?: number;
  /** voltage deviation × 10 (no float), e.g. 20.0% → 200. */
  deviationPercentPermille?: number;
  detectedAt: string;
  resolvedAt?: string;
  description?: string;
  createdAt: string;
}

export interface FaultView extends FaultRecord {
  faultUri: string;
}

export interface ReportFaultInput {
  faultId: string;
  faultKind: FaultKind;
  feederId: string;
  affectedCustomers?: number;
  deviationPercentPermille?: number;
  detectedAt: string;
  resolvedAt?: string;
  description?: string;
}

export interface ReportFaultOutput {
  status: "reported" | "alreadyExists" | "rejected";
  faultUri?: string;
  did?: string;
  faultId?: string;
  severity?: FaultSeverity;
  publicNotice?: boolean;
  error?: string;
}

export type DemandResponseKind = "voluntary" | "mandatory" | "emergency";

export interface DemandResponseRecord {
  did: string;
  drId: string;
  drKind: DemandResponseKind;
  targetFeederDids: string[];
  /** Target load reduction in kW (whole). */
  targetReductionKw: number;
  /** Actual achieved reduction in kW. */
  actualReductionKw?: number;
  startsAt: string;
  endsAt: string;
  triggerReason?: string;
  createdAt: string;
}

export interface RecordDemandResponseInput {
  drId: string;
  drKind: DemandResponseKind;
  targetFeederDids: string[];
  targetReductionKw: number;
  actualReductionKw?: number;
  startsAt: string;
  endsAt: string;
  triggerReason?: string;
}

export interface RecordDemandResponseOutput {
  status: "recorded" | "alreadyExists" | "rejected";
  drUri?: string;
  did?: string;
  drId?: string;
  error?: string;
}

export interface RenewableOutputRecord {
  did: string;
  nodeId: string;
  nodeDid: string;
  nodeKind: "solar" | "wind" | "hydro" | "storage";
  outputSeq: number;
  /** Output in kW. */
  outputKw: number;
  /** capacity factor × 1000 (0-1000) for renewables. */
  capacityFactorPermille?: number;
  observedAt: string;
  createdAt: string;
}

export interface RecordRenewableOutputInput {
  nodeId: string;
  outputSeq: number;
  outputKw: number;
  capacityFactorPermille?: number;
  observedAt: string;
}

export interface RecordRenewableOutputOutput {
  status:
    | "recorded"
    | "alreadyExists"
    | "rejected"
    | "notRenewable";
  outputUri?: string;
  did?: string;
  nodeId?: string;
  outputSeq?: number;
  error?: string;
}

// ─── Query tier (slice 3) ───────────────────────────────────────────

export interface ListFeedersInput {
  substationDid?: string;
  status?: FeederStatus;
  voltageLevel?: VoltageLevel;
  limit?: number;
  cursor?: string;
}

export interface ListFeedersOutput {
  items: FeederView[];
  cursor?: string;
  total: number;
}

export interface ListFaultsInput {
  feederDid?: string;
  since?: string;
  minSeverity?: FaultSeverity;
  publicNoticeOnly?: boolean;
  limit?: number;
  cursor?: string;
}

export interface ListFaultsOutput {
  items: FaultView[];
  cursor?: string;
  total: number;
}

export interface ListReadingsInput {
  meterDid?: string;
  since?: string;
  limit?: number;
  cursor?: string;
}

export interface ListReadingsOutput {
  items: MeterReadingView[];
  cursor?: string;
  total: number;
}

// ─── Slug helpers ───────────────────────────────────────────────────

export function idSlug(id: string): string {
  return id.toLowerCase().replace(/[^a-z0-9]/g, "-");
}

export function generationNodeDid(nodeId: string): string {
  return `${OPEN_DENKI_DID_PREFIX}gen:${idSlug(nodeId)}`;
}

export function generationNodeRkey(nodeId: string): string {
  return `gen-${idSlug(nodeId)}`;
}

export function substationDid(substationId: string): string {
  return `${OPEN_DENKI_DID_PREFIX}sub:${idSlug(substationId)}`;
}

export function substationRkey(substationId: string): string {
  return `sub-${idSlug(substationId)}`;
}

export function feederDid(feederId: string): string {
  return `${OPEN_DENKI_DID_PREFIX}feeder:${idSlug(feederId)}`;
}

export function feederRkey(feederId: string): string {
  return `feeder-${idSlug(feederId)}`;
}

export function smartMeterDid(meterId: string): string {
  return `${OPEN_DENKI_DID_PREFIX}meter:${idSlug(meterId)}`;
}

export function smartMeterRkey(meterId: string): string {
  return `meter-${idSlug(meterId)}`;
}
