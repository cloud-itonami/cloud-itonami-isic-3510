# open-denki.etzhayyim.com — Smart Grid Operations & Network Design (OSS)

**Status**: MVP (2026-05-07). Reference implementation for smart-grid topology
design (generation nodes / substations / feeders / AMI smart meters) and
operations (meter readings, fault reports, demand response, renewable output).
Apache-2.0. IEC 61968/61970 CIM aligned.

## Scope (MVP)

| NSID | Type | Description |
|---|---|---|
| `com.etzhayyim.apps.openDenki.defineGenerationNode` | procedure | generation node (solar / wind / hydro / thermal / nuclear / storage) |
| `com.etzhayyim.apps.openDenki.defineSubstation` | procedure | HV/MV/LV transformer substation |
| `com.etzhayyim.apps.openDenki.defineFeeder` | procedure | distribution feeder (substation → delivery points) |
| `com.etzhayyim.apps.openDenki.registerSmartMeter` | procedure | AMI meter (consumption / generation / bidirectional) |
| `com.etzhayyim.apps.openDenki.recordMeterReading` | procedure | kWh + kW demand reading (monotonic for consumption) |
| `com.etzhayyim.apps.openDenki.reportFault` | procedure | fault with severity DMN + optional public notice |
| `com.etzhayyim.apps.openDenki.recordDemandResponse` | procedure | DR event (voluntary / mandatory / emergency) |
| `com.etzhayyim.apps.openDenki.recordRenewableOutput` | procedure | renewable generation output (solar / wind / hydro / storage only) |
| `com.etzhayyim.apps.openDenki.getNode` | query | substation or generation node detail |
| `com.etzhayyim.apps.openDenki.listFeeders` | query | feeders by substation / status |
| `com.etzhayyim.apps.openDenki.listFaults` | query | faults by feeder / since / minSeverity |
| `com.etzhayyim.apps.openDenki.listReadings` | query | meter readings by meter / since |

## Architecture

- **Runtime**: Single CF Worker (`worker/src/app.ts`)
- **Storage**: D1. Tables: `substations`, `gen_nodes`, `feeders`, `smart_meters`, `meter_readings`, `faults`, `demand_response_events`, `renewable_output`
- **Identity**: all entities use path-based DIDs
  `did:web:open-denki.etzhayyim.com:{sub|gen|feeder|meter|fault|dr|output}:{id}`
- **Topology**: generation nodes + substations → feeders → smart meters
- **Fault severity** by DMN (`openDenki.faultSeverity`):
  - `earth_fault` / `short_circuit` → critical + public notice
  - `outage` ≥100 customers → critical; ≥10 → major + public notice
  - `voltage_deviation` ≥20% → major; ≥10% → moderate
  - `overload` ≥50 customers → major + public notice
- **Monotonic constraint**: consumption meters enforce non-decreasing kWh
- **Renewable guard**: `recordRenewableOutput` accepts only solar/wind/hydro/storage gen types
- **Public notice**: severity ≥ major → `app.bsky.feed.post` via PDS

## Compliance

- IEC 61968 CIM (Distribution Management)
- IEC 61970 CIM (Energy Management System)

## Downstream consumer: `etzhayyim-project-open-ot` (2026-05-15)

`open-ot` (WASM-native PLC + Distributed Logic Controller, ADR-2605151200) consumes open-denki as **configuration SSoT** and adds **control verbs** on top:

| open-denki provides (config / event SSoT) | open-ot adds (control verb) |
|---|---|
| `defineGenerationNode`, `defineSubstation`, `defineFeeder`, `registerSmartMeter` | `defineDevice`, `defineCell`, `defineLoop` for the controllers acting on those assets |
| `recordMeterReading`, `recordRenewableOutput` | consumed (not duplicated); aggregated into `recordTelemetryBatch` for control read tier |
| `recordDemandResponse` | triggers `setpointChange` cascade across BESS / curtailable PV / load |
| `reportFault` (CIM fault) | `reportFault` (control-side fault) carries optional `cimFaultDid` cross-link |

First open-ot prototype is a **community microgrid** (per ADR §R3, scope in `60-apps/etzhayyim-project-open-ot/PROTOTYPE-MICROGRID.md`). open-denki record DIDs are referenced directly from open-ot loop / cell manifests; no schema duplication.

## Not in MVP

- Power flow / load flow simulation (Newton-Raphson)
- SCADA real-time telemetry (IEC 60870-5-104 / DNP3)
- Market dispatch (JEPX / nodal pricing)
- Battery state-of-charge modeling
- Grid topology optimization / Volt-VAr control

## Local Dev / Deploy

```bash
cd 60-apps/etzhayyim-project-open-denki/worker
wrangler d1 create etzhayyim-open-denki
# set OPEN_DENKI_D1_ID in .dev.vars
e7m actor deploy .
```
