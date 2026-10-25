// event_body.js -- the template-shared adapter for isic/open-* domain events
// (JUN-410's template migration design, step 1). Pure only.
//
// The template family (open-denki / open-rail / open-ports / open-network /
// open-gas / open-banking, isic-3510/3520/5221/5222/6110/6419, ...) keeps its
// domain EVENTS on the quorum-certified append path: each event is
// canonically serialized (plain concatenation, the engi.core /
// canonical-transfer-body style: no JSON/EDN printer, byte-identical across
// runtimes), content-addressed by its CID, and admitted only when its
// constraint checks pass. SQL CHECK/UNIQUE stops being the adjudicator; the
// append admission is.
//
// Entities (substations, meters, ...) do NOT come through here: their history
// is the kotobase datom log (append-only, replayable, history-datoms
// queryable) -- measured 2026-10-25, JUN-410.
//
// Field order per type is fixed and part of the format. A new event type is a
// new entry here, never a free-form map.

const TYPES = Object.freeze({
  "meter-reading": ["meterDid", "seq", "prevKwh", "kwh", "kw", "ts"],
  "fault": ["faultDid", "feederDid", "severity", "customers", "notice", "ts"],
  "demand-response": ["drDid", "feederDid", "mode", "ts"],
  "renewable-output": ["outputDid", "genDid", "genType", "kwh", "ts"],
});

const f = (v) => (v === null || v === undefined ? "nil" : String(v));

export function canonicalEventBody(type, fields) {
  const order = TYPES[type];
  if (!order) throw new Error(`unknown event type: ${type}`);
  let out = `event/v1\n${type}\n`;
  for (const k of order) out += `${k}=${f(fields[k])}\n`;
  return out;
}

// Fail-closed shape check: every declared field present and well-typed. An
// event that cannot name its fields is never serialized, let alone admitted.
export function validateEvent(type, fields) {
  const order = TYPES[type];
  if (!order) return { ok: false, reason: "unknown-type" };
  for (const k of order) {
    if (!(k in (fields ?? {}))) return { ok: false, reason: "missing-field", field: k };
    if (fields[k] === null || fields[k] === undefined) return { ok: false, reason: "nil-field", field: k };
  }
  if (type === "meter-reading") {
    const { seq, prevKwh, kwh, kw } = fields;
    if (!Number.isInteger(seq) || seq < 0) return { ok: false, reason: "bad-seq" };
    if (!Number.isInteger(kwh) || kwh < 0) return { ok: false, reason: "bad-kwh" };
    if (!Number.isInteger(prevKwh) || prevKwh < 0) return { ok: false, reason: "bad-prev-kwh" };
    if (!Number.isInteger(kw) || kw < 0) return { ok: false, reason: "bad-kw" };
  }
  return { ok: true };
}

// The monotonicity adjudication that SQL CHECK used to do: consumption meter
// readings are non-decreasing in kWh and strictly sequential in seq. This is
// the append admission for the meter's chain (the engi prev-hash pattern
// without the hash: per-meter seq is the order).
export function admitReading(prev, reading) {
  const v = validateEvent("meter-reading", reading);
  if (!v.ok) return v;
  if (!prev) return { ok: true, first: true };
  if (reading.seq !== prev.seq + 1) return { ok: false, reason: "seq-not-sequential" };
  if (reading.prevKwh !== prev.kwh) return { ok: false, reason: "prev-kwh-mismatch" };
  if (reading.kwh < prev.kwh) return { ok: false, reason: "kwh-not-monotonic" };
  return { ok: true };
}
