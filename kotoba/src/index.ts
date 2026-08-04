/**
 * open-denki kotoba — barrel.
 *
 * Per ADR-2605203000 Option B Phase E reference impl. Smart-grid topology
 * design + operations (IEC 61968/61970 CIM aligned).
 *
 * Slice 1: 5 of 12 canonical commands ported.
 *   defineGenerationNode + defineSubstation + defineFeeder +
 *   registerSmartMeter + getNode
 *
 * Follow-up slices:
 * - Operation events: recordMeterReading, reportFault, recordDemandResponse, recordRenewableOutput
 * - Queries: listFeeders, listFaults, listReadings
 *
 * All numeric fields use integer units (kW, kV, MVA, meters) to satisfy
 * AT Lexicon no-float restriction.
 */

export * from "./types.js";
export {
  defineGenerationNode,
  defineSubstation,
  defineFeeder,
  registerSmartMeter,
  getNode,
} from "./topology.js";
export {
  recordMeterReading,
  reportFault,
  recordDemandResponse,
  recordRenewableOutput,
} from "./events.js";
export { listFeeders, listFaults, listReadings } from "./queries.js";
