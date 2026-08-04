# open-denki kotoba

Phase E Option B reference implementation of open-denki (Smart-Grid Operations + Network Design) on the etzhayyim substrate.

Per [ADR-2605203000](../../../90-docs/adr/2605203000-kotoba-write-target-options.md), open-denki migrates from vendor's `D1 (SQLite)` pattern to **Option B** — PDS XRPC writes via `@etzhayyim/sdk e.write()`.

Coverage: **12 of 12 (100%) canonical** open-denki commands ported.

| Tier | Commands | Slice |
|---|---|---|
| Topology | defineGenerationNode, defineSubstation, defineFeeder, registerSmartMeter, getNode | 1 |
| Events | recordMeterReading, reportFault, recordDemandResponse, recordRenewableOutput | 2 |
| Queries | listFeeders, listFaults, listReadings | **3** |

All 12 canonical open-denki commands now have kotoba reference impl. Wire-up
to a Worker / LangServer pod XRPC handler is the next operator task.

## Topology hierarchy (IEC 61968/61970 CIM aligned)

```
GenerationNode (solar/wind/hydro/thermal/nuclear/storage)
        ┐
        │
Substation (HV/MV/LV transformer)
        │
        ↓
   Feeder (substation → delivery)
        │
        ↓
 SmartMeter (consumption / generation / bidirectional)
        │
        ↓
 MeterReading (monotonic for consumption)   ← future slice
```

## Authority-chain DIDs (per CLAUDE.md)

```
did:web:open-denki.etzhayyim.com:gen:{nodeId-slug}       — GenerationNode
did:web:open-denki.etzhayyim.com:sub:{substationId-slug} — Substation
did:web:open-denki.etzhayyim.com:feeder:{feederId-slug}  — Feeder
did:web:open-denki.etzhayyim.com:meter:{meterId-slug}    — SmartMeter
did:web:open-denki.etzhayyim.com:reading:{...}           — MeterReading (future)
did:web:open-denki.etzhayyim.com:fault:{id}              — Fault (future)
did:web:open-denki.etzhayyim.com:dr:{id}                 — DemandResponse (future)
did:web:open-denki.etzhayyim.com:output:{...}            — RenewableOutput (future)
```

## No-float numeric units

All physical quantities use integer units to satisfy AT Lexicon no-float restriction:

| Quantity | Unit | Notes |
|---|---|---|
| `capacityKw` | kW (integer) | generation nameplate capacity |
| `primaryVoltageKv` | kV (integer) | substation primary voltage |
| `secondaryVoltageKv` | kV (integer) | substation secondary voltage |
| `capacityMva` | MVA (integer) | substation capacity |
| `customerCount` | integer | feeder customer count |
| `lengthM` | meters (integer) | feeder length |

## Usage

```ts
import { Etzhayyim } from "@etzhayyim/sdk";
import { defineSubstation, defineFeeder, registerSmartMeter } from "@etzhayyim/open-denki-kotoba";

const e = new Etzhayyim({
  did: "did:web:open-denki.etzhayyim.com",
  pdsUrl: "https://pds.etzhayyim.com",
  l2RpcUrl: "https://mainnet.base.org",
});

const sub = await defineSubstation(e, {
  substationId: "sub-tokyo-shibuya-001",
  name: "Shibuya Distribution Substation",
  kind: "distribution",
  primaryVoltageKv: 66,
  secondaryVoltageKv: 22,
  capacityMva: 100,
  operatorDid: "did:web:tepco.example",
});

const feeder = await defineFeeder(e, {
  feederId: "feeder-shibuya-fl1",
  name: "Shibuya FL-1",
  substationId: "sub-tokyo-shibuya-001",
  voltageLevel: "lv",
  customerCount: 450,
  lengthM: 1850,
});

const meter = await registerSmartMeter(e, {
  meterId: "meter-shibuya-fl1-001",
  feederId: "feeder-shibuya-fl1",
  kind: "bidirectional",   // supports rooftop solar export
  ownerDid: "did:plc:customer...",
});
```

## Why Option B for open-denki

Per ADR-2605203000 Phase E decision matrix:
- **Catalog**: topology records (small, slow-moving) + operation events (high-frequency reads, moderate write rate)
- **Write cadence**: topology = per-design (slow); meter readings = per-interval (15-min to hourly)
- **Query pattern**: by feederDid (delivery analysis), by substationDid (load forecast), by meterDid + time range (billing)

Option A (vendor RW mirror) rejected — ADR-2605172000 mandates kotoba.

## Sibling reference impls

| Actor | Coverage | Status |
|---|---|---|
| hanrei | 31/31 (100%) | complete |
| ipaddress | 37/37 (100%) | complete |
| sbom | 17/N (canonical 4/4) | canonical complete |
| kiyo | 12/12 (100%) | complete |
| ki | 4/4 (100%) | complete |
| otakiage | 13 (10/10 canonical) | complete |
| houki | 9 (8/8 canonical) | complete |
| open-banking | 5/5 (100%) | complete |
| **open-denki** | **5/12** | **active** |
