/**
 * open-denki kotoba — topology tier (slice 1, 5/12 canonical).
 *
 *   defineGenerationNode — generation node (solar / wind / hydro / thermal / nuclear / storage)
 *   defineSubstation     — HV/MV/LV transformer
 *   defineFeeder         — substation → delivery feeder
 *   registerSmartMeter   — AMI meter
 *   getNode              — substation or generation node detail
 *
 * IEC 61968/61970 CIM aligned (Distribution Management + Energy
 * Management). All numeric fields use integer units (kW, kV, MVA, meters)
 * to satisfy AT Lexicon no-float restriction.
 *
 * Slice 1 covers the static configuration tier; operation events
 * (recordMeterReading, reportFault, recordDemandResponse,
 * recordRenewableOutput, listReadings, listFaults, listFeeders) ship
 * in follow-up slices.
 */

import type { Etzhayyim } from "@etzhayyim/sdk";
import {
  feederDid,
  feederRkey,
  generationNodeDid,
  generationNodeRkey,
  smartMeterDid,
  smartMeterRkey,
  substationDid,
  substationRkey,
  type AnyNodeView,
  type DefineFeederInput,
  type DefineFeederOutput,
  type DefineGenerationNodeInput,
  type DefineGenerationNodeOutput,
  type DefineSubstationInput,
  type DefineSubstationOutput,
  type FeederRecord,
  type GenerationNodeRecord,
  type GenerationNodeView,
  type GetNodeInput,
  type GetNodeOutput,
  type RegisterSmartMeterInput,
  type RegisterSmartMeterOutput,
  type SmartMeterRecord,
  type SubstationRecord,
  type SubstationView,
} from "./types.js";

const GEN_COLLECTION = "com.etzhayyim.apps.openDenki.generationNode";
const SUB_COLLECTION = "com.etzhayyim.apps.openDenki.substation";
const FEEDER_COLLECTION = "com.etzhayyim.apps.openDenki.feeder";
const METER_COLLECTION = "com.etzhayyim.apps.openDenki.smartMeter";

export async function defineGenerationNode(
  e: Etzhayyim,
  input: DefineGenerationNodeInput
): Promise<DefineGenerationNodeOutput> {
  if (
    !input.nodeId ||
    !input.name ||
    !input.kind ||
    typeof input.capacityKw !== "number" ||
    input.capacityKw <= 0
  ) {
    return { status: "rejected", error: "missingOrInvalidFields" };
  }
  const rkey = generationNodeRkey(input.nodeId);
  const existing = await e
    .read<GenerationNodeRecord>({ collection: GEN_COLLECTION, rkey })
    .catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return {
      status: "alreadyExists",
      generationNodeUri: existing.records[0].uri,
      did: existing.records[0].value.did,
      nodeId: input.nodeId,
    };
  }
  const did = generationNodeDid(input.nodeId);
  const now = new Date().toISOString();
  const record: GenerationNodeRecord = {
    did,
    nodeId: input.nodeId,
    name: input.name,
    kind: input.kind,
    capacityKw: input.capacityKw,
    voltageLevel: input.voltageLevel,
    operatorDid: input.operatorDid,
    locationHint: input.locationHint,
    commissionedAt: input.commissionedAt,
    createdAt: now,
  };
  const receipt = await e.write({
    collection: GEN_COLLECTION,
    record: record as unknown as Record<string, unknown>,
    rkey,
  });
  return {
    status: "registered",
    generationNodeUri: receipt.uri,
    did,
    nodeId: input.nodeId,
  };
}

export async function defineSubstation(
  e: Etzhayyim,
  input: DefineSubstationInput
): Promise<DefineSubstationOutput> {
  if (
    !input.substationId ||
    !input.name ||
    !input.kind ||
    typeof input.primaryVoltageKv !== "number" ||
    typeof input.secondaryVoltageKv !== "number" ||
    typeof input.capacityMva !== "number"
  ) {
    return { status: "rejected", error: "missingOrInvalidFields" };
  }
  const rkey = substationRkey(input.substationId);
  const existing = await e
    .read<SubstationRecord>({ collection: SUB_COLLECTION, rkey })
    .catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return {
      status: "alreadyExists",
      substationUri: existing.records[0].uri,
      did: existing.records[0].value.did,
      substationId: input.substationId,
    };
  }
  const did = substationDid(input.substationId);
  const now = new Date().toISOString();
  const record: SubstationRecord = {
    did,
    substationId: input.substationId,
    name: input.name,
    kind: input.kind,
    primaryVoltageKv: input.primaryVoltageKv,
    secondaryVoltageKv: input.secondaryVoltageKv,
    capacityMva: input.capacityMva,
    operatorDid: input.operatorDid,
    locationHint: input.locationHint,
    commissionedAt: input.commissionedAt,
    createdAt: now,
  };
  const receipt = await e.write({
    collection: SUB_COLLECTION,
    record: record as unknown as Record<string, unknown>,
    rkey,
  });
  return {
    status: "registered",
    substationUri: receipt.uri,
    did,
    substationId: input.substationId,
  };
}

export async function defineFeeder(
  e: Etzhayyim,
  input: DefineFeederInput
): Promise<DefineFeederOutput> {
  if (
    !input.feederId ||
    !input.name ||
    !input.substationId ||
    !input.voltageLevel ||
    typeof input.customerCount !== "number"
  ) {
    return { status: "rejected", error: "missingOrInvalidFields" };
  }
  // Verify parent substation exists.
  const subResp = await e
    .read<SubstationRecord>({
      collection: SUB_COLLECTION,
      rkey: substationRkey(input.substationId),
    })
    .catch(() => ({ records: [] }));
  if (!subResp.records[0]?.value) return { status: "substationNotFound" };

  const rkey = feederRkey(input.feederId);
  const existing = await e
    .read<FeederRecord>({ collection: FEEDER_COLLECTION, rkey })
    .catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return {
      status: "alreadyExists",
      feederUri: existing.records[0].uri,
      did: existing.records[0].value.did,
      feederId: input.feederId,
    };
  }
  const did = feederDid(input.feederId);
  const now = new Date().toISOString();
  const record: FeederRecord = {
    did,
    feederId: input.feederId,
    name: input.name,
    substationDid: subResp.records[0].value.did,
    voltageLevel: input.voltageLevel,
    customerCount: input.customerCount,
    status: input.status ?? "active",
    lengthM: input.lengthM,
    createdAt: now,
  };
  const receipt = await e.write({
    collection: FEEDER_COLLECTION,
    record: record as unknown as Record<string, unknown>,
    rkey,
  });
  return {
    status: "registered",
    feederUri: receipt.uri,
    did,
    feederId: input.feederId,
  };
}

export async function registerSmartMeter(
  e: Etzhayyim,
  input: RegisterSmartMeterInput
): Promise<RegisterSmartMeterOutput> {
  if (!input.meterId || !input.kind) {
    return { status: "rejected", error: "missingRequiredFields" };
  }
  let feederDidValue: string | undefined;
  if (input.feederId) {
    const fResp = await e
      .read<FeederRecord>({
        collection: FEEDER_COLLECTION,
        rkey: feederRkey(input.feederId),
      })
      .catch(() => ({ records: [] }));
    if (!fResp.records[0]?.value) return { status: "feederNotFound" };
    feederDidValue = fResp.records[0].value.did;
  }
  const rkey = smartMeterRkey(input.meterId);
  const existing = await e
    .read<SmartMeterRecord>({ collection: METER_COLLECTION, rkey })
    .catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return {
      status: "alreadyExists",
      smartMeterUri: existing.records[0].uri,
      did: existing.records[0].value.did,
      meterId: input.meterId,
    };
  }
  const did = smartMeterDid(input.meterId);
  const now = new Date().toISOString();
  const record: SmartMeterRecord = {
    did,
    meterId: input.meterId,
    serialNumber: input.serialNumber,
    feederDid: feederDidValue,
    kind: input.kind,
    ownerDid: input.ownerDid,
    installedAt: input.installedAt,
    createdAt: now,
  };
  const receipt = await e.write({
    collection: METER_COLLECTION,
    record: record as unknown as Record<string, unknown>,
    rkey,
  });
  return {
    status: "registered",
    smartMeterUri: receipt.uri,
    did,
    meterId: input.meterId,
  };
}

export async function getNode(
  e: Etzhayyim,
  input: GetNodeInput
): Promise<GetNodeOutput> {
  if (!input.nodeId) return { error: "missingNodeId" };
  // Try requested kind first, fall back to the other.
  const tryKinds: ("generation" | "substation")[] = input.kind
    ? [input.kind, input.kind === "generation" ? "substation" : "generation"]
    : ["generation", "substation"];
  for (const k of tryKinds) {
    if (k === "generation") {
      const r = await e
        .read<GenerationNodeRecord>({
          collection: GEN_COLLECTION,
          rkey: generationNodeRkey(input.nodeId),
        })
        .catch(() => ({ records: [] }));
      if (r.records[0]?.value) {
        const view: AnyNodeView = {
          ...r.records[0].value,
          generationNodeUri: r.records[0].uri,
          _kind: "generation",
        } as GenerationNodeView & { _kind: "generation" };
        return { node: view };
      }
    } else {
      const r = await e
        .read<SubstationRecord>({
          collection: SUB_COLLECTION,
          rkey: substationRkey(input.nodeId),
        })
        .catch(() => ({ records: [] }));
      if (r.records[0]?.value) {
        const view: AnyNodeView = {
          ...r.records[0].value,
          substationUri: r.records[0].uri,
          _kind: "substation",
        } as SubstationView & { _kind: "substation" };
        return { node: view };
      }
    }
  }
  return { error: "notFound" };
}
