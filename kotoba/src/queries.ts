/**
 * open-denki kotoba — queries tier (slice 3, +3 → 12/12 canonical ✓).
 *
 *   listFeeders   — cursor + substation/status/voltage filter
 *   listFaults    — cursor + feeder/since/minSeverity filter
 *   listReadings  — cursor + meter/since filter
 *
 * Closes open-denki kotoba at 12/12 canonical lexicons.
 */

import type { Etzhayyim } from "@etzhayyim/sdk";
import {
  type FaultRecord,
  type FaultSeverity,
  type FaultView,
  type FeederRecord,
  type FeederStatus,
  type FeederView,
  type ListFaultsInput,
  type ListFaultsOutput,
  type ListFeedersInput,
  type ListFeedersOutput,
  type ListReadingsInput,
  type ListReadingsOutput,
  type MeterReadingRecord,
  type MeterReadingView,
  type VoltageLevel,
} from "./types.js";

const FEEDER_COLLECTION = "com.etzhayyim.apps.openDenki.feeder";
const FAULT_COLLECTION = "com.etzhayyim.apps.openDenki.fault";
const READING_COLLECTION = "com.etzhayyim.apps.openDenki.meterReading";

const SEVERITY_ORDER: Record<FaultSeverity, number> = {
  minor: 0,
  moderate: 1,
  major: 2,
  critical: 3,
};

export async function listFeeders(
  e: Etzhayyim,
  input: ListFeedersInput = {}
): Promise<ListFeedersOutput> {
  const limit = Math.min(input.limit ?? 50, 100);
  const resp = await e.read<FeederRecord>({
    collection: FEEDER_COLLECTION,
    cursor: input.cursor,
    limit,
  });
  const items: FeederView[] = resp.records
    .filter((r) => {
      const v = r.value;
      if (input.substationDid && v.substationDid !== input.substationDid) {
        return false;
      }
      if (input.status && v.status !== input.status) return false;
      if (input.voltageLevel && v.voltageLevel !== input.voltageLevel) {
        return false;
      }
      return true;
    })
    .map((r) => ({ ...r.value, feederUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

export async function listFaults(
  e: Etzhayyim,
  input: ListFaultsInput = {}
): Promise<ListFaultsOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<FaultRecord>({
    collection: FAULT_COLLECTION,
    cursor: input.cursor,
    limit,
  });
  const minSev = input.minSeverity
    ? SEVERITY_ORDER[input.minSeverity]
    : SEVERITY_ORDER.minor;
  const items: FaultView[] = resp.records
    .filter((r) => {
      const v = r.value;
      if (input.feederDid && v.feederDid !== input.feederDid) return false;
      if (input.since && v.detectedAt < input.since) return false;
      if (SEVERITY_ORDER[v.severity] < minSev) return false;
      if (input.publicNoticeOnly && !v.publicNotice) return false;
      return true;
    })
    .map((r) => ({ ...r.value, faultUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

export async function listReadings(
  e: Etzhayyim,
  input: ListReadingsInput = {}
): Promise<ListReadingsOutput> {
  const limit = Math.min(input.limit ?? 100, 200);
  const resp = await e.read<MeterReadingRecord>({
    collection: READING_COLLECTION,
    cursor: input.cursor,
    limit,
  });
  const items: MeterReadingView[] = resp.records
    .filter((r) => {
      const v = r.value;
      if (input.meterDid && v.meterDid !== input.meterDid) return false;
      if (input.since && v.readAt < input.since) return false;
      return true;
    })
    .map((r) => ({ ...r.value, readingUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

/** Re-exports so callers don't reach into types.ts directly. */
export type {
  FeederStatus,
  FaultSeverity,
  VoltageLevel,
};
