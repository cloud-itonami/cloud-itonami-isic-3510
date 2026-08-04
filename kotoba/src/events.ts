/**
 * open-denki kotoba — operation events tier (slice 2, +4 → 9/12).
 *
 *   recordMeterReading    — kWh + kW reading (monotonic for consumption meters)
 *   reportFault           — fault with DMN-derived severity (critical/major/moderate/minor)
 *   recordDemandResponse  — DR event (voluntary/mandatory/emergency)
 *   recordRenewableOutput — renewable generation (solar/wind/hydro/storage ONLY)
 *
 * Append-only events: each event gets its own rkey so historical records
 * never overwrite. recordMeterReading enforces non-decreasing kWh for
 * consumption-kind meters (rejects with `nonMonotonic` on regression).
 *
 * Fault severity DMN (per CLAUDE.md openDenki.faultSeverity):
 *   earth_fault / short_circuit       → critical + public notice
 *   outage ≥100 customers              → critical
 *   outage ≥10 customers               → major + public notice
 *   voltage_deviation ≥20%             → major
 *   voltage_deviation ≥10%             → moderate
 *   overload ≥50 customers             → major + public notice
 */

import type { Etzhayyim } from "@etzhayyim/sdk";
import {
  feederRkey,
  generationNodeRkey,
  idSlug,
  OPEN_DENKI_DID_PREFIX,
  smartMeterRkey,
  type DemandResponseRecord,
  type FaultRecord,
  type FaultSeverity,
  type FeederRecord,
  type GenerationNodeRecord,
  type MeterReadingRecord,
  type RecordDemandResponseInput,
  type RecordDemandResponseOutput,
  type RecordMeterReadingInput,
  type RecordMeterReadingOutput,
  type RecordRenewableOutputInput,
  type RecordRenewableOutputOutput,
  type RenewableOutputRecord,
  type ReportFaultInput,
  type ReportFaultOutput,
  type SmartMeterRecord,
} from "./types.js";

const FEEDER_COLLECTION = "com.etzhayyim.apps.openDenki.feeder";
const METER_COLLECTION = "com.etzhayyim.apps.openDenki.smartMeter";
const GEN_COLLECTION = "com.etzhayyim.apps.openDenki.generationNode";
const READING_COLLECTION = "com.etzhayyim.apps.openDenki.meterReading";
const FAULT_COLLECTION = "com.etzhayyim.apps.openDenki.fault";
const DR_COLLECTION = "com.etzhayyim.apps.openDenki.demandResponse";
const OUTPUT_COLLECTION = "com.etzhayyim.apps.openDenki.renewableOutput";

const PAGE_LIMIT = 100;
const DEFAULT_MAX_SCAN = 10_000;

function readingRkey(meterId: string, seq: number): string {
  const padded = String(seq).padStart(8, "0");
  return `reading-${idSlug(meterId)}-${padded}`;
}

function readingDid(meterId: string, seq: number): string {
  const padded = String(seq).padStart(8, "0");
  return `${OPEN_DENKI_DID_PREFIX}reading:${idSlug(meterId)}-${padded}`;
}

function faultRkey(faultId: string): string {
  return `fault-${idSlug(faultId)}`;
}

function faultDid(faultId: string): string {
  return `${OPEN_DENKI_DID_PREFIX}fault:${idSlug(faultId)}`;
}

function drRkey(drId: string): string {
  return `dr-${idSlug(drId)}`;
}

function drDid(drId: string): string {
  return `${OPEN_DENKI_DID_PREFIX}dr:${idSlug(drId)}`;
}

function outputRkey(genId: string, seq: number): string {
  const padded = String(seq).padStart(8, "0");
  return `output-${idSlug(genId)}-${padded}`;
}

function outputDid(genId: string, seq: number): string {
  const padded = String(seq).padStart(8, "0");
  return `${OPEN_DENKI_DID_PREFIX}output:${idSlug(genId)}-${padded}`;
}

// ─── recordMeterReading (monotonic for consumption) ─────────────────

export async function recordMeterReading(
  e: Etzhayyim,
  input: RecordMeterReadingInput
): Promise<RecordMeterReadingOutput> {
  if (
    !input.meterId ||
    typeof input.readingSeq !== "number" ||
    typeof input.kwhCumulative !== "number" ||
    !input.readAt
  ) {
    return { status: "rejected", error: "missingOrInvalidFields" };
  }
  const meterResp = await e
    .read<SmartMeterRecord>({
      collection: METER_COLLECTION,
      rkey: smartMeterRkey(input.meterId),
    })
    .catch(() => ({ records: [] }));
  const meter = meterResp.records[0]?.value;
  if (!meter) return { status: "rejected", error: "meterNotFound" };

  // Monotonic check for consumption meters.
  if (meter.kind === "consumption" && input.readingSeq > 1) {
    // Look up previous reading by walking back from current seq.
    const prevRkey = readingRkey(input.meterId, input.readingSeq - 1);
    const prevResp = await e
      .read<MeterReadingRecord>({
        collection: READING_COLLECTION,
        rkey: prevRkey,
      })
      .catch(() => ({ records: [] }));
    const prev = prevResp.records[0]?.value;
    if (prev && input.kwhCumulative < prev.kwhCumulative) {
      return { status: "nonMonotonic", error: "kwhRegression" };
    }
  }

  const rkey = readingRkey(input.meterId, input.readingSeq);
  const existing = await e
    .read<MeterReadingRecord>({ collection: READING_COLLECTION, rkey })
    .catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return {
      status: "alreadyExists",
      readingUri: existing.records[0].uri,
      meterId: input.meterId,
      readingSeq: input.readingSeq,
    };
  }
  const did = readingDid(input.meterId, input.readingSeq);
  const now = new Date().toISOString();
  const record: MeterReadingRecord = {
    did,
    meterDid: meter.did,
    meterId: input.meterId,
    readingSeq: input.readingSeq,
    kwhCumulative: input.kwhCumulative,
    /** Instantaneous demand in kW. */
    kwDemand: input.kwDemand,
    /** Voltage in volts (integer). */
    voltageV: input.voltageV,
    readAt: input.readAt,
    createdAt: now,
  };
  const receipt = await e.write({
    collection: READING_COLLECTION,
    record: record as unknown as Record<string, unknown>,
    rkey,
  });
  return {
    status: "recorded",
    readingUri: receipt.uri,
    did,
    meterId: input.meterId,
    readingSeq: input.readingSeq,
  };
}

// ─── reportFault (with DMN-derived severity) ────────────────────────

function deriveSeverity(
  faultKind: ReportFaultInput["faultKind"],
  metric: ReportFaultInput
): { severity: FaultSeverity; publicNotice: boolean } {
  if (faultKind === "earth_fault" || faultKind === "short_circuit") {
    return { severity: "critical", publicNotice: true };
  }
  if (faultKind === "outage") {
    const c = metric.affectedCustomers ?? 0;
    if (c >= 100) return { severity: "critical", publicNotice: true };
    if (c >= 10) return { severity: "major", publicNotice: true };
    return { severity: "minor", publicNotice: false };
  }
  if (faultKind === "voltage_deviation") {
    const d = metric.deviationPercentPermille ?? 0;
    if (d >= 200) return { severity: "major", publicNotice: false };
    if (d >= 100) return { severity: "moderate", publicNotice: false };
    return { severity: "minor", publicNotice: false };
  }
  if (faultKind === "overload") {
    const c = metric.affectedCustomers ?? 0;
    if (c >= 50) return { severity: "major", publicNotice: true };
    return { severity: "minor", publicNotice: false };
  }
  return { severity: "minor", publicNotice: false };
}

export async function reportFault(
  e: Etzhayyim,
  input: ReportFaultInput
): Promise<ReportFaultOutput> {
  if (!input.faultId || !input.faultKind || !input.feederId || !input.detectedAt) {
    return { status: "rejected", error: "missingRequiredFields" };
  }
  const feederResp = await e
    .read<FeederRecord>({
      collection: FEEDER_COLLECTION,
      rkey: feederRkey(input.feederId),
    })
    .catch(() => ({ records: [] }));
  if (!feederResp.records[0]?.value) {
    return { status: "rejected", error: "feederNotFound" };
  }
  const rkey = faultRkey(input.faultId);
  const existing = await e
    .read<FaultRecord>({ collection: FAULT_COLLECTION, rkey })
    .catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return {
      status: "alreadyExists",
      faultUri: existing.records[0].uri,
      did: existing.records[0].value.did,
      faultId: input.faultId,
    };
  }
  const { severity, publicNotice } = deriveSeverity(input.faultKind, input);
  const did = faultDid(input.faultId);
  const now = new Date().toISOString();
  const record: FaultRecord = {
    did,
    faultId: input.faultId,
    faultKind: input.faultKind,
    feederDid: feederResp.records[0].value.did,
    severity,
    publicNotice,
    affectedCustomers: input.affectedCustomers,
    deviationPercentPermille: input.deviationPercentPermille,
    detectedAt: input.detectedAt,
    resolvedAt: input.resolvedAt,
    description: input.description,
    createdAt: now,
  };
  const receipt = await e.write({
    collection: FAULT_COLLECTION,
    record: record as unknown as Record<string, unknown>,
    rkey,
  });
  return {
    status: "reported",
    faultUri: receipt.uri,
    did,
    faultId: input.faultId,
    severity,
    publicNotice,
  };
}

// ─── recordDemandResponse ───────────────────────────────────────────

export async function recordDemandResponse(
  e: Etzhayyim,
  input: RecordDemandResponseInput
): Promise<RecordDemandResponseOutput> {
  if (!input.drId || !input.drKind || !input.startsAt || !input.endsAt) {
    return { status: "rejected", error: "missingRequiredFields" };
  }
  const rkey = drRkey(input.drId);
  const existing = await e
    .read<DemandResponseRecord>({ collection: DR_COLLECTION, rkey })
    .catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return {
      status: "alreadyExists",
      drUri: existing.records[0].uri,
      did: existing.records[0].value.did,
      drId: input.drId,
    };
  }
  const did = drDid(input.drId);
  const now = new Date().toISOString();
  const record: DemandResponseRecord = {
    did,
    drId: input.drId,
    drKind: input.drKind,
    targetFeederDids: input.targetFeederDids,
    targetReductionKw: input.targetReductionKw,
    actualReductionKw: input.actualReductionKw,
    startsAt: input.startsAt,
    endsAt: input.endsAt,
    triggerReason: input.triggerReason,
    createdAt: now,
  };
  const receipt = await e.write({
    collection: DR_COLLECTION,
    record: record as unknown as Record<string, unknown>,
    rkey,
  });
  return {
    status: "recorded",
    drUri: receipt.uri,
    did,
    drId: input.drId,
  };
}

// ─── recordRenewableOutput (renewable guard) ────────────────────────

const RENEWABLE_KINDS = new Set(["solar", "wind", "hydro", "storage"] as const);

export async function recordRenewableOutput(
  e: Etzhayyim,
  input: RecordRenewableOutputInput
): Promise<RecordRenewableOutputOutput> {
  if (
    !input.nodeId ||
    typeof input.outputSeq !== "number" ||
    typeof input.outputKw !== "number" ||
    !input.observedAt
  ) {
    return { status: "rejected", error: "missingOrInvalidFields" };
  }
  const genResp = await e
    .read<GenerationNodeRecord>({
      collection: GEN_COLLECTION,
      rkey: generationNodeRkey(input.nodeId),
    })
    .catch(() => ({ records: [] }));
  const gen = genResp.records[0]?.value;
  if (!gen) return { status: "rejected", error: "generationNodeNotFound" };
  if (!RENEWABLE_KINDS.has(gen.kind as "solar" | "wind" | "hydro" | "storage")) {
    return { status: "notRenewable", error: `kind:${gen.kind}NotRenewable` };
  }
  const rkey = outputRkey(input.nodeId, input.outputSeq);
  const existing = await e
    .read<RenewableOutputRecord>({ collection: OUTPUT_COLLECTION, rkey })
    .catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return {
      status: "alreadyExists",
      outputUri: existing.records[0].uri,
      did: existing.records[0].value.did,
      nodeId: input.nodeId,
      outputSeq: input.outputSeq,
    };
  }
  const did = outputDid(input.nodeId, input.outputSeq);
  const now = new Date().toISOString();
  const record: RenewableOutputRecord = {
    did,
    nodeId: input.nodeId,
    nodeDid: gen.did,
    nodeKind: gen.kind as "solar" | "wind" | "hydro" | "storage",
    outputSeq: input.outputSeq,
    outputKw: input.outputKw,
    /** capacity factor × 1000 (0-1000) for renewables. */
    capacityFactorPermille: input.capacityFactorPermille,
    observedAt: input.observedAt,
    createdAt: now,
  };
  const receipt = await e.write({
    collection: OUTPUT_COLLECTION,
    record: record as unknown as Record<string, unknown>,
    rkey,
  });
  return {
    status: "recorded",
    outputUri: receipt.uri,
    did,
    nodeId: input.nodeId,
    outputSeq: input.outputSeq,
  };
}
