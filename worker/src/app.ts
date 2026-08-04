// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 etzhayyim Japan株式会社 / etzhayyim. All rights reserved.
// Licensed under the Apache License, Version 2.0 — see LICENSE at repo root.

// etzhayyim-project-open-denki — smart-grid network design + operations
//
// 12 XRPC under com.etzhayyim.apps.openDenki.*:
//   defineGenerationNode   (proc)   thermal / hydro / solar / wind / nuclear / storage
//   defineSubstation       (proc)   HV/MV/LV transformer substation
//   defineFeeder           (proc)   distribution feeder (substation → delivery points)
//   registerSmartMeter     (proc)   AMI meter (consumption / generation / bidirectional)
//   recordMeterReading     (proc)   kWh + kW demand reading
//   reportFault            (proc)   fault with severity DMN (voltage / outage / SC / EF)
//   recordDemandResponse   (proc)   DR event (voluntary / mandatory / emergency)
//   recordRenewableOutput  (proc)   renewable generation output record
//   getNode                (query)  generation node or substation detail
//   listFeeders            (query)  feeders by substation / status
//   listFaults             (query)  faults by feeder / since / minSeverity
//   listReadings           (query)  meter readings by meter / since
//
// Storage: D1. Topology = gen_nodes + substations (sources) → feeders (edges) → smart_meters (sinks).
// Fault severity via openDenki.faultSeverity DMN (mirrored). IEC 61968/61970 CIM aligned.

export interface Env {
  DENKI_DB: D1Database;
  PDS?: Fetcher;
  AUTH_SERVICE?: Fetcher;
  APP_HANDLE: string;
  PRIMARY_DID: string;
}

const GEN_TYPES = ["solar", "wind", "hydro", "thermal", "nuclear", "storage", "other"] as const;
const FAULT_TYPES = ["voltage_deviation", "outage", "short_circuit", "earth_fault", "overload"] as const;
const DR_TYPES = ["voluntary", "mandatory", "emergency"] as const;
const METER_TYPES = ["consumption", "generation", "bidirectional"] as const;

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS substations (
    sub_did TEXT PRIMARY KEY,
    sub_code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    voltage_kv REAL NOT NULL,
    operator_did TEXT NOT NULL,
    defined_at TEXT NOT NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_sub_code ON substations(sub_code)`,
  `CREATE TABLE IF NOT EXISTS gen_nodes (
    gen_did TEXT PRIMARY KEY,
    gen_code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    gen_type TEXT NOT NULL,
    substation_did TEXT,
    capacity_kw REAL NOT NULL,
    operator_did TEXT NOT NULL,
    defined_at TEXT NOT NULL,
    FOREIGN KEY (substation_did) REFERENCES substations(sub_did)
  )`,
  `CREATE INDEX IF NOT EXISTS idx_gen_sub ON gen_nodes(substation_did)`,
  `CREATE TABLE IF NOT EXISTS feeders (
    feeder_did TEXT PRIMARY KEY,
    feeder_code TEXT NOT NULL UNIQUE,
    substation_did TEXT NOT NULL,
    length_km REAL NOT NULL,
    voltage_kv REAL NOT NULL,
    rating_kva REAL NOT NULL,
    status TEXT NOT NULL DEFAULT 'in-service',
    defined_at TEXT NOT NULL,
    FOREIGN KEY (substation_did) REFERENCES substations(sub_did)
  )`,
  `CREATE INDEX IF NOT EXISTS idx_feeder_sub ON feeders(substation_did)`,
  `CREATE TABLE IF NOT EXISTS smart_meters (
    meter_did TEXT PRIMARY KEY,
    meter_code TEXT NOT NULL UNIQUE,
    feeder_did TEXT NOT NULL,
    meter_type TEXT NOT NULL,
    capacity_kva REAL,
    registered_at TEXT NOT NULL,
    FOREIGN KEY (feeder_did) REFERENCES feeders(feeder_did)
  )`,
  `CREATE INDEX IF NOT EXISTS idx_meter_feeder ON smart_meters(feeder_did)`,
  `CREATE TABLE IF NOT EXISTS meter_readings (
    reading_id TEXT PRIMARY KEY,
    meter_did TEXT NOT NULL,
    feeder_did TEXT NOT NULL,
    read_at TEXT NOT NULL,
    kwh REAL NOT NULL,
    kw_demand REAL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_reading_meter_time ON meter_readings(meter_did, read_at DESC)`,
  `CREATE TABLE IF NOT EXISTS faults (
    fault_did TEXT PRIMARY KEY,
    feeder_did TEXT NOT NULL,
    fault_type TEXT NOT NULL,
    detected_at TEXT NOT NULL,
    restored_at TEXT,
    affected_customers INTEGER NOT NULL DEFAULT 0,
    voltage_pct REAL,
    severity TEXT NOT NULL,
    require_public_notice INTEGER NOT NULL,
    description TEXT,
    reported_at TEXT NOT NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_fault_feeder_time ON faults(feeder_did, detected_at DESC)`,
  `CREATE TABLE IF NOT EXISTS demand_response_events (
    dr_did TEXT PRIMARY KEY,
    feeder_did TEXT NOT NULL,
    dr_type TEXT NOT NULL,
    started_at TEXT NOT NULL,
    ended_at TEXT,
    target_kw REAL NOT NULL,
    achieved_kw REAL,
    reported_at TEXT NOT NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_dr_feeder_time ON demand_response_events(feeder_did, started_at DESC)`,
  `CREATE TABLE IF NOT EXISTS renewable_output (
    output_did TEXT PRIMARY KEY,
    gen_did TEXT NOT NULL,
    recorded_at TEXT NOT NULL,
    kwh_output REAL NOT NULL,
    capacity_factor REAL,
    reported_at TEXT NOT NULL,
    FOREIGN KEY (gen_did) REFERENCES gen_nodes(gen_did)
  )`,
  `CREATE INDEX IF NOT EXISTS idx_output_gen_time ON renewable_output(gen_did, recorded_at DESC)`,
];

let schemaReady = false;
async function ensureSchema(db: D1Database) {
  if (schemaReady) return;
  for (const s of SCHEMA) await db.exec(s.replace(/\s+/g, " "));
  schemaReady = true;
}

function nanoid(len = 12): string {
  const a = "abcdefghijklmnopqrstuvwxyz0123456789";
  const b = crypto.getRandomValues(new Uint8Array(len));
  let o = ""; for (let i = 0; i < len; i++) o += a[b[i] % a.length]; return o;
}
const now = () => new Date().toISOString();

type XrpcError =
  | "InvalidRequest" | "NotFound" | "SubstationNotFound"
  | "FeederNotFound" | "MeterNotFound" | "GenNodeNotFound"
  | "Conflict" | "Unauthorized" | "InternalError";
const json = (b: unknown, s = 200) =>
  new Response(JSON.stringify(b), { status: s, headers: { "content-type": "application/json" } });
const err = (e: XrpcError, m: string, s = 400) => json({ error: e, message: m }, s);

// IEC 61968 CIM-aligned fault severity DMN
function classifyFault(input: {
  faultType: string;
  affectedCustomers: number;
  voltagePct?: number;
}): { severity: "minor" | "moderate" | "major" | "critical"; requirePublicNotice: boolean } {
  const { faultType, affectedCustomers, voltagePct } = input;
  if (faultType === "earth_fault") return { severity: "critical", requirePublicNotice: true };
  if (faultType === "short_circuit") return { severity: "critical", requirePublicNotice: true };
  if (faultType === "outage" && affectedCustomers >= 100)
    return { severity: "critical", requirePublicNotice: true };
  if (faultType === "outage" && affectedCustomers >= 10)
    return { severity: "major", requirePublicNotice: true };
  if (faultType === "voltage_deviation") {
    const dev = voltagePct != null ? Math.abs(100 - voltagePct) : 0;
    if (dev >= 20) return { severity: "major", requirePublicNotice: true };
    if (dev >= 10) return { severity: "moderate", requirePublicNotice: false };
    return { severity: "minor", requirePublicNotice: false };
  }
  if (faultType === "overload" && affectedCustomers >= 50)
    return { severity: "major", requirePublicNotice: true };
  if (affectedCustomers >= 10) return { severity: "moderate", requirePublicNotice: false };
  return { severity: "minor", requirePublicNotice: false };
}

async function defineGenerationNode(env: Env, input: any): Promise<Response> {
  const { genCode, name, genType, substationDid, capacityKw, operatorDid } = input ?? {};
  if (typeof genCode !== "string" || !/^[A-Z0-9-]{2,32}$/.test(genCode))
    return err("InvalidRequest", "genCode required (A-Z0-9-, 2..32)");
  if (typeof name !== "string" || !name.length)
    return err("InvalidRequest", "name required");
  if (!GEN_TYPES.includes(genType))
    return err("InvalidRequest", `genType must be one of: ${GEN_TYPES.join(", ")}`);
  if (!Number.isFinite(capacityKw) || capacityKw <= 0)
    return err("InvalidRequest", "capacityKw > 0 required");
  if (typeof operatorDid !== "string" || !operatorDid.startsWith("did:"))
    return err("InvalidRequest", "operatorDid must be a DID");
  if (substationDid != null) {
    const sub = await env.DENKI_DB.prepare(`SELECT sub_did FROM substations WHERE sub_did = ?`)
      .bind(substationDid).first<any>();
    if (!sub) return err("SubstationNotFound", "substation not found", 404);
  }
  const id = nanoid(10);
  const genDid = `did:web:${env.APP_HANDLE}:gen:${id}`;
  const definedAt = now();
  await env.DENKI_DB.prepare(
    `INSERT INTO gen_nodes (gen_did, gen_code, name, gen_type, substation_did, capacity_kw, operator_did, defined_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(genDid, genCode, name, genType, substationDid ?? null, capacityKw, operatorDid, definedAt).run();
  return json({ genDid, genCode, name, genType, substationDid: substationDid ?? null, capacityKw, operatorDid, definedAt });
}

async function defineSubstation(env: Env, input: any): Promise<Response> {
  const { subCode, name, voltageKv, operatorDid } = input ?? {};
  if (typeof subCode !== "string" || !/^[A-Z0-9-]{2,32}$/.test(subCode))
    return err("InvalidRequest", "subCode required (A-Z0-9-, 2..32)");
  if (typeof name !== "string" || !name.length)
    return err("InvalidRequest", "name required");
  if (!Number.isFinite(voltageKv) || voltageKv <= 0)
    return err("InvalidRequest", "voltageKv > 0 required");
  if (typeof operatorDid !== "string" || !operatorDid.startsWith("did:"))
    return err("InvalidRequest", "operatorDid must be a DID");
  const id = nanoid(10);
  const subDid = `did:web:${env.APP_HANDLE}:sub:${id}`;
  const definedAt = now();
  await env.DENKI_DB.prepare(
    `INSERT INTO substations (sub_did, sub_code, name, voltage_kv, operator_did, defined_at)
     VALUES (?, ?, ?, ?, ?, ?)`
  ).bind(subDid, subCode, name, voltageKv, operatorDid, definedAt).run();
  return json({ subDid, subCode, name, voltageKv, operatorDid, definedAt });
}

async function defineFeeder(env: Env, input: any): Promise<Response> {
  const { substationDid, feederCode, lengthKm, voltageKv, ratingKva } = input ?? {};
  if (typeof substationDid !== "string")
    return err("InvalidRequest", "substationDid required");
  if (typeof feederCode !== "string" || !/^[A-Z0-9-]{2,16}$/.test(feederCode))
    return err("InvalidRequest", "feederCode required (A-Z0-9-, 2..16)");
  if (!Number.isFinite(lengthKm) || lengthKm <= 0)
    return err("InvalidRequest", "lengthKm > 0 required");
  if (!Number.isFinite(voltageKv) || voltageKv <= 0)
    return err("InvalidRequest", "voltageKv > 0 required");
  if (!Number.isFinite(ratingKva) || ratingKva <= 0)
    return err("InvalidRequest", "ratingKva > 0 required");
  const sub = await env.DENKI_DB.prepare(`SELECT sub_did FROM substations WHERE sub_did = ?`)
    .bind(substationDid).first<any>();
  if (!sub) return err("SubstationNotFound", "substation not found", 404);
  const id = nanoid(10);
  const feederDid = `did:web:${env.APP_HANDLE}:feeder:${id}`;
  const definedAt = now();
  await env.DENKI_DB.prepare(
    `INSERT INTO feeders (feeder_did, feeder_code, substation_did, length_km, voltage_kv, rating_kva, status, defined_at)
     VALUES (?, ?, ?, ?, ?, ?, 'in-service', ?)`
  ).bind(feederDid, feederCode, substationDid, lengthKm, voltageKv, ratingKva, definedAt).run();
  return json({ feederDid, feederCode, substationDid, lengthKm, voltageKv, ratingKva, status: "in-service", definedAt });
}

async function registerSmartMeter(env: Env, input: any): Promise<Response> {
  const { feederDid, meterCode, meterType, capacityKva } = input ?? {};
  if (typeof feederDid !== "string")
    return err("InvalidRequest", "feederDid required");
  if (typeof meterCode !== "string" || !/^[A-Z0-9-]{2,32}$/.test(meterCode))
    return err("InvalidRequest", "meterCode required (A-Z0-9-, 2..32)");
  if (!METER_TYPES.includes(meterType))
    return err("InvalidRequest", `meterType must be one of: ${METER_TYPES.join(", ")}`);
  if (capacityKva != null && (!Number.isFinite(capacityKva) || capacityKva <= 0))
    return err("InvalidRequest", "capacityKva > 0");
  const f = await env.DENKI_DB.prepare(`SELECT feeder_did FROM feeders WHERE feeder_did = ?`)
    .bind(feederDid).first<any>();
  if (!f) return err("FeederNotFound", "feeder not found", 404);
  const id = nanoid(10);
  const meterDid = `did:web:${env.APP_HANDLE}:meter:${id}`;
  const registeredAt = now();
  await env.DENKI_DB.prepare(
    `INSERT INTO smart_meters (meter_did, meter_code, feeder_did, meter_type, capacity_kva, registered_at)
     VALUES (?, ?, ?, ?, ?, ?)`
  ).bind(meterDid, meterCode, feederDid, meterType, capacityKva ?? null, registeredAt).run();
  return json({ meterDid, meterCode, feederDid, meterType, capacityKva: capacityKva ?? null, registeredAt });
}

async function recordMeterReading(env: Env, input: any): Promise<Response> {
  const { meterDid, readAt, kwh, kwDemand } = input ?? {};
  if (typeof meterDid !== "string") return err("InvalidRequest", "meterDid required");
  if (typeof readAt !== "string") return err("InvalidRequest", "readAt required");
  if (!Number.isFinite(kwh)) return err("InvalidRequest", "kwh required (may be negative for generation)");
  if (kwDemand != null && !Number.isFinite(kwDemand))
    return err("InvalidRequest", "kwDemand must be numeric");
  const m = await env.DENKI_DB.prepare(`SELECT * FROM smart_meters WHERE meter_did = ?`)
    .bind(meterDid).first<any>();
  if (!m) return err("MeterNotFound", "smart meter not found", 404);
  // monotonic check: consumption meters must not decrease
  if (m.meter_type === "consumption") {
    const last = await env.DENKI_DB.prepare(
      `SELECT kwh FROM meter_readings WHERE meter_did = ? ORDER BY read_at DESC LIMIT 1`
    ).bind(meterDid).first<{ kwh: number }>();
    if (last && kwh < last.kwh) return err("Conflict", "kWh reading not monotonic for consumption meter", 409);
  }
  const id = `r_${nanoid(14)}`;
  await env.DENKI_DB.prepare(
    `INSERT INTO meter_readings (reading_id, meter_did, feeder_did, read_at, kwh, kw_demand)
     VALUES (?, ?, ?, ?, ?, ?)`
  ).bind(id, meterDid, m.feeder_did, readAt, kwh, kwDemand ?? null).run();
  return json({ readingId: id, meterDid, feederDid: m.feeder_did, readAt, kwh, kwDemand: kwDemand ?? null });
}

async function reportFault(env: Env, input: any): Promise<Response> {
  const { feederDid, faultType, detectedAt, restoredAt, affectedCustomers, voltagePct, description } = input ?? {};
  if (typeof feederDid !== "string") return err("InvalidRequest", "feederDid required");
  if (!FAULT_TYPES.includes(faultType))
    return err("InvalidRequest", `faultType must be one of: ${FAULT_TYPES.join(", ")}`);
  if (typeof detectedAt !== "string") return err("InvalidRequest", "detectedAt required");
  const affected = Number(affectedCustomers ?? 0);
  if (!Number.isInteger(affected) || affected < 0)
    return err("InvalidRequest", "affectedCustomers must be non-negative integer");
  if (voltagePct != null && (!Number.isFinite(voltagePct) || voltagePct < 0 || voltagePct > 200))
    return err("InvalidRequest", "voltagePct must be 0..200");
  const f = await env.DENKI_DB.prepare(`SELECT feeder_did FROM feeders WHERE feeder_did = ?`)
    .bind(feederDid).first<any>();
  if (!f) return err("FeederNotFound", "feeder not found", 404);
  const { severity, requirePublicNotice } = classifyFault({ faultType, affectedCustomers: affected, voltagePct });
  const id = nanoid(12);
  const faultDid = `did:web:${env.APP_HANDLE}:fault:${id}`;
  const reportedAt = now();
  await env.DENKI_DB.prepare(
    `INSERT INTO faults (fault_did, feeder_did, fault_type, detected_at, restored_at, affected_customers, voltage_pct, severity, require_public_notice, description, reported_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(faultDid, feederDid, faultType, detectedAt, restoredAt ?? null,
         affected, voltagePct ?? null, severity, requirePublicNotice ? 1 : 0,
         description ?? null, reportedAt).run();
  if (requirePublicNotice && env.PDS) {
    try {
      await env.PDS.fetch(`https://atproto.etzhayyim.com/xrpc/com.atproto.repo.createRecord`, {
        method: "POST", headers: { "content-type": "application/json" },
        body: JSON.stringify({
          repo: env.PRIMARY_DID, collection: "app.bsky.feed.post",
          record: {
            $type: "app.bsky.feed.post",
            text: `[${severity.toUpperCase()}] grid fault on ${feederDid} — ${faultType}${affected > 0 ? `, ${affected} customers affected` : ""}`,
            createdAt: reportedAt,
          },
        }),
      });
    } catch {}
  }
  return json({ faultDid, feederDid, faultType, severity, requirePublicNotice, affectedCustomers: affected, reportedAt });
}

async function recordDemandResponse(env: Env, input: any): Promise<Response> {
  const { feederDid, drType, startedAt, endedAt, targetKw, achievedKw } = input ?? {};
  if (typeof feederDid !== "string") return err("InvalidRequest", "feederDid required");
  if (!DR_TYPES.includes(drType))
    return err("InvalidRequest", `drType must be one of: ${DR_TYPES.join(", ")}`);
  if (typeof startedAt !== "string") return err("InvalidRequest", "startedAt required");
  if (!Number.isFinite(targetKw) || targetKw <= 0)
    return err("InvalidRequest", "targetKw > 0 required");
  if (achievedKw != null && (!Number.isFinite(achievedKw) || achievedKw < 0))
    return err("InvalidRequest", "achievedKw ≥ 0");
  const f = await env.DENKI_DB.prepare(`SELECT feeder_did FROM feeders WHERE feeder_did = ?`)
    .bind(feederDid).first<any>();
  if (!f) return err("FeederNotFound", "feeder not found", 404);
  const id = nanoid(12);
  const drDid = `did:web:${env.APP_HANDLE}:dr:${id}`;
  const reportedAt = now();
  await env.DENKI_DB.prepare(
    `INSERT INTO demand_response_events (dr_did, feeder_did, dr_type, started_at, ended_at, target_kw, achieved_kw, reported_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(drDid, feederDid, drType, startedAt, endedAt ?? null, targetKw, achievedKw ?? null, reportedAt).run();
  return json({ drDid, feederDid, drType, startedAt, endedAt: endedAt ?? null, targetKw, achievedKw: achievedKw ?? null, reportedAt });
}

async function recordRenewableOutput(env: Env, input: any): Promise<Response> {
  const { genDid, recordedAt, kwhOutput, capacityFactor } = input ?? {};
  if (typeof genDid !== "string") return err("InvalidRequest", "genDid required");
  if (typeof recordedAt !== "string") return err("InvalidRequest", "recordedAt required");
  if (!Number.isFinite(kwhOutput) || kwhOutput < 0)
    return err("InvalidRequest", "kwhOutput ≥ 0 required");
  if (capacityFactor != null && (!Number.isFinite(capacityFactor) || capacityFactor < 0 || capacityFactor > 1))
    return err("InvalidRequest", "capacityFactor must be 0..1");
  const g = await env.DENKI_DB.prepare(`SELECT gen_type FROM gen_nodes WHERE gen_did = ?`)
    .bind(genDid).first<any>();
  if (!g) return err("GenNodeNotFound", "generation node not found", 404);
  const renewable = ["solar", "wind", "hydro", "storage"].includes(g.gen_type);
  if (!renewable) return err("InvalidRequest", `gen_type '${g.gen_type}' is not a renewable type`);
  const id = nanoid(12);
  const outputDid = `did:web:${env.APP_HANDLE}:output:${id}`;
  const reportedAt = now();
  await env.DENKI_DB.prepare(
    `INSERT INTO renewable_output (output_did, gen_did, recorded_at, kwh_output, capacity_factor, reported_at)
     VALUES (?, ?, ?, ?, ?, ?)`
  ).bind(outputDid, genDid, recordedAt, kwhOutput, capacityFactor ?? null, reportedAt).run();
  return json({ outputDid, genDid, genType: g.gen_type, recordedAt, kwhOutput, capacityFactor: capacityFactor ?? null, reportedAt });
}

async function getNode(env: Env, params: URLSearchParams): Promise<Response> {
  const subDid = params.get("subDid");
  const genDid = params.get("genDid");
  if (!subDid && !genDid) return err("InvalidRequest", "subDid or genDid required");
  if (subDid) {
    const s = await env.DENKI_DB.prepare(`SELECT * FROM substations WHERE sub_did = ?`)
      .bind(subDid).first<any>();
    if (!s) return err("SubstationNotFound", "no such substation", 404);
    const feeders = await env.DENKI_DB.prepare(
      `SELECT * FROM feeders WHERE substation_did = ? ORDER BY defined_at DESC`
    ).bind(subDid).all<any>();
    const genNodes = await env.DENKI_DB.prepare(
      `SELECT gen_did, gen_code, name, gen_type, capacity_kw FROM gen_nodes WHERE substation_did = ?`
    ).bind(subDid).all<any>();
    return json({
      nodeType: "substation",
      subDid: s.sub_did, subCode: s.sub_code, name: s.name,
      voltageKv: s.voltage_kv, operatorDid: s.operator_did, definedAt: s.defined_at,
      feeders: (feeders.results ?? []).map((f) => ({
        feederDid: f.feeder_did, feederCode: f.feeder_code,
        lengthKm: f.length_km, voltageKv: f.voltage_kv, ratingKva: f.rating_kva, status: f.status,
      })),
      genNodes: (genNodes.results ?? []).map((g) => ({
        genDid: g.gen_did, genCode: g.gen_code, name: g.name, genType: g.gen_type, capacityKw: g.capacity_kw,
      })),
    });
  }
  const g = await env.DENKI_DB.prepare(`SELECT * FROM gen_nodes WHERE gen_did = ?`)
    .bind(genDid!).first<any>();
  if (!g) return err("GenNodeNotFound", "no such generation node", 404);
  return json({
    nodeType: "genNode",
    genDid: g.gen_did, genCode: g.gen_code, name: g.name, genType: g.gen_type,
    substationDid: g.substation_did ?? undefined, capacityKw: g.capacity_kw,
    operatorDid: g.operator_did, definedAt: g.defined_at,
  });
}

async function listFeeders(env: Env, params: URLSearchParams): Promise<Response> {
  const substationDid = params.get("substationDid");
  const status = params.get("status");
  const limit = Math.min(500, Math.max(1, Number(params.get("limit") ?? 100)));
  const offset = Math.max(0, Number(params.get("offset") ?? 0));
  const clauses: string[] = []; const binds: any[] = [];
  if (substationDid) { clauses.push(`substation_did = ?`); binds.push(substationDid); }
  if (status) { clauses.push(`status = ?`); binds.push(status); }
  const where = clauses.length ? `WHERE ${clauses.join(" AND ")}` : "";
  const total = await env.DENKI_DB.prepare(`SELECT COUNT(*) AS c FROM feeders ${where}`)
    .bind(...binds).first<{ c: number }>();
  const rows = await env.DENKI_DB.prepare(
    `SELECT * FROM feeders ${where} ORDER BY defined_at DESC LIMIT ? OFFSET ?`
  ).bind(...binds, limit, offset).all<any>();
  return json({
    feeders: (rows.results ?? []).map((f) => ({
      feederDid: f.feeder_did, feederCode: f.feeder_code, substationDid: f.substation_did,
      lengthKm: f.length_km, voltageKv: f.voltage_kv, ratingKva: f.rating_kva,
      status: f.status, definedAt: f.defined_at,
    })),
    total: Number(total?.c ?? 0), offset, limit,
  });
}

async function listFaults(env: Env, params: URLSearchParams): Promise<Response> {
  const feederDid = params.get("feederDid");
  const since = params.get("since");
  const minSeverity = params.get("minSeverity");
  const limit = Math.min(500, Math.max(1, Number(params.get("limit") ?? 100)));
  const offset = Math.max(0, Number(params.get("offset") ?? 0));
  const rank = (s: string) => ({ minor: 0, moderate: 1, major: 2, critical: 3 } as any)[s] ?? -1;
  const clauses: string[] = []; const binds: any[] = [];
  if (feederDid) { clauses.push(`feeder_did = ?`); binds.push(feederDid); }
  if (since) { clauses.push(`detected_at >= ?`); binds.push(since); }
  const where = clauses.length ? `WHERE ${clauses.join(" AND ")}` : "";
  const total = await env.DENKI_DB.prepare(`SELECT COUNT(*) AS c FROM faults ${where}`)
    .bind(...binds).first<{ c: number }>();
  const rows = await env.DENKI_DB.prepare(
    `SELECT * FROM faults ${where} ORDER BY detected_at DESC LIMIT ? OFFSET ?`
  ).bind(...binds, limit, offset).all<any>();
  let faults = (rows.results ?? []).map((f) => ({
    faultDid: f.fault_did, feederDid: f.feeder_did, faultType: f.fault_type,
    detectedAt: f.detected_at, restoredAt: f.restored_at ?? undefined,
    affectedCustomers: f.affected_customers, voltagePct: f.voltage_pct ?? undefined,
    severity: f.severity, requirePublicNotice: !!f.require_public_notice,
    description: f.description ?? undefined, reportedAt: f.reported_at,
  }));
  if (minSeverity) faults = faults.filter((f) => rank(f.severity) >= rank(minSeverity));
  return json({ faults, total: Number(total?.c ?? 0), offset, limit });
}

async function listReadings(env: Env, params: URLSearchParams): Promise<Response> {
  const meterDid = params.get("meterDid");
  if (!meterDid) return err("InvalidRequest", "meterDid required");
  const since = params.get("since");
  const limit = Math.min(500, Math.max(1, Number(params.get("limit") ?? 100)));
  const offset = Math.max(0, Number(params.get("offset") ?? 0));
  const clauses = [`meter_did = ?`]; const binds: any[] = [meterDid];
  if (since) { clauses.push(`read_at >= ?`); binds.push(since); }
  const where = `WHERE ${clauses.join(" AND ")}`;
  const total = await env.DENKI_DB.prepare(`SELECT COUNT(*) AS c FROM meter_readings ${where}`)
    .bind(...binds).first<{ c: number }>();
  const rows = await env.DENKI_DB.prepare(
    `SELECT * FROM meter_readings ${where} ORDER BY read_at DESC LIMIT ? OFFSET ?`
  ).bind(...binds, limit, offset).all<any>();
  return json({
    readings: (rows.results ?? []).map((r) => ({
      readingId: r.reading_id, meterDid: r.meter_did, feederDid: r.feeder_did,
      readAt: r.read_at, kwh: r.kwh, kwDemand: r.kw_demand ?? undefined,
    })),
    total: Number(total?.c ?? 0), offset, limit,
  });
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    try {
      await ensureSchema(env.DENKI_DB);
      const url = new URL(req.url);
      if (url.pathname === "/health" || url.pathname === "/_worker/health")
        return json({ ok: true, did: env.PRIMARY_DID, ts: now() });
      if (url.pathname === "/_app/meta")
        return json({
          did: env.PRIMARY_DID, handle: env.APP_HANDLE,
          xrpc: [
            "com.etzhayyim.apps.openDenki.defineGenerationNode",
            "com.etzhayyim.apps.openDenki.defineSubstation",
            "com.etzhayyim.apps.openDenki.defineFeeder",
            "com.etzhayyim.apps.openDenki.registerSmartMeter",
            "com.etzhayyim.apps.openDenki.recordMeterReading",
            "com.etzhayyim.apps.openDenki.reportFault",
            "com.etzhayyim.apps.openDenki.recordDemandResponse",
            "com.etzhayyim.apps.openDenki.recordRenewableOutput",
            "com.etzhayyim.apps.openDenki.getNode",
            "com.etzhayyim.apps.openDenki.listFeeders",
            "com.etzhayyim.apps.openDenki.listFaults",
            "com.etzhayyim.apps.openDenki.listReadings",
          ],
          dmn: ["openDenki.faultSeverity"],
          compliance: ["iec-61968-cim", "iec-61970-cim"],
        });
      if (!url.pathname.startsWith("/xrpc/"))
        return err("InvalidRequest", "only /xrpc/* is served", 404);
      const nsid = url.pathname.slice("/xrpc/".length);
      if (req.method === "GET") {
        switch (nsid) {
          case "com.etzhayyim.apps.openDenki.getNode":      return await getNode(env, url.searchParams);
          case "com.etzhayyim.apps.openDenki.listFeeders":  return await listFeeders(env, url.searchParams);
          case "com.etzhayyim.apps.openDenki.listFaults":   return await listFaults(env, url.searchParams);
          case "com.etzhayyim.apps.openDenki.listReadings": return await listReadings(env, url.searchParams);
          default: return err("InvalidRequest", `unknown query NSID: ${nsid}`, 404);
        }
      }
      if (req.method === "POST") {
        const body = await req.json().catch(() => ({}));
        switch (nsid) {
          case "com.etzhayyim.apps.openDenki.defineGenerationNode":  return await defineGenerationNode(env, body);
          case "com.etzhayyim.apps.openDenki.defineSubstation":      return await defineSubstation(env, body);
          case "com.etzhayyim.apps.openDenki.defineFeeder":          return await defineFeeder(env, body);
          case "com.etzhayyim.apps.openDenki.registerSmartMeter":    return await registerSmartMeter(env, body);
          case "com.etzhayyim.apps.openDenki.recordMeterReading":    return await recordMeterReading(env, body);
          case "com.etzhayyim.apps.openDenki.reportFault":           return await reportFault(env, body);
          case "com.etzhayyim.apps.openDenki.recordDemandResponse":  return await recordDemandResponse(env, body);
          case "com.etzhayyim.apps.openDenki.recordRenewableOutput": return await recordRenewableOutput(env, body);
          default: return err("InvalidRequest", `unknown procedure NSID: ${nsid}`, 404);
        }
      }
      return err("InvalidRequest", "method not allowed", 405);
    } catch (e: any) {
      return err("InternalError", e?.message ?? String(e), 500);
    }
  },
};
