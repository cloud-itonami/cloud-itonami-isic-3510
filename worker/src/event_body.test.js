// event_body.test.js -- the template adapter's format and adjudication are
// pinned here: a fixed field order, fail-closed validation, and the
// monotonicity check that replaces SQL CHECK (JUN-410).
import test from "node:test";
import assert from "node:assert/strict";
import { canonicalEventBody, validateEvent, admitReading } from "./event_body.js";

const reading = { meterDid: "did:key:zM", seq: 3, prevKwh: 100, kwh: 105, kw: 2, ts: 1730000000000 };

test("canonical form: fixed field order, plain concatenation, nil literal", () => {
  assert.equal(
    canonicalEventBody("meter-reading", reading),
    "event/v1\nmeter-reading\nmeterDid=did:key:zM\nseq=3\nprevKwh=100\nkwh=105\nkw=2\nts=1730000000000\n",
  );
  const nilField = canonicalEventBody("fault", { faultDid: "f1", feederDid: "fd", severity: "outage", customers: 3, notice: undefined, ts: 1 });
  assert.ok(nilField.includes("notice=nil\n"));
  assert.throws(() => canonicalEventBody("nope", {}), /unknown event type/);
});

test("validateEvent is fail-closed: missing or nil fields never pass", () => {
  assert.equal(validateEvent("meter-reading", reading).ok, true);
  assert.equal(validateEvent("meter-reading", { ...reading, kwh: undefined }).ok, false);
  assert.equal(validateEvent("meter-reading", { ...reading, seq: -1 }).ok, false);
  assert.equal(validateEvent("meter-reading", { ...reading, kwh: 1.5 }).ok, false);
  assert.equal(validateEvent("unknown-type", {}).ok, false);
  const fault = { faultDid: "f1", feederDid: "fd", severity: "outage", customers: 3, notice: true, ts: 1 };
  assert.equal(validateEvent("fault", fault).ok, true);
  assert.equal(validateEvent("fault", { ...fault, severity: null }).ok, false);
});

test("admitReading: the meter chain is sequential and non-decreasing", () => {
  const prev = { seq: 2, kwh: 100 };
  assert.equal(admitReading(prev, reading).ok, true);
  assert.equal(admitReading(null, reading).ok, true, "first reading is admitted");
  assert.equal(admitReading(prev, { ...reading, seq: 5 }).reason, "seq-not-sequential");
  assert.equal(admitReading(prev, { ...reading, prevKwh: 99 }).reason, "prev-kwh-mismatch");
  const down = admitReading(prev, { ...reading, kwh: 90, prevKwh: 100 });
  assert.equal(down.ok, false);
  assert.equal(down.reason, "kwh-not-monotonic");
  assert.equal(admitReading(prev, { ...reading, kwh: 100 }).ok, true, "equal kWh is allowed (non-decreasing)");
});

test("admitReading refuses invalid events before any chain check", () => {
  assert.equal(admitReading({ seq: 2, kwh: 100 }, { ...reading, kw: -1 }).ok, false);
});
