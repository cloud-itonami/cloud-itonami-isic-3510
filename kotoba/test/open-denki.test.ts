import { describe, it, expect, beforeEach } from "vitest";
import { MockEtzhayyim } from "@etzhayyim/sdk-mock";
import {
  defineGenerationNode,
  defineSubstation,
  defineFeeder,
  registerSmartMeter,
  recordMeterReading,
  getNode,
} from "../src/index.js";

describe("open-denki kotoba", () => {
  let e: any;

  beforeEach(() => {
    e = new MockEtzhayyim({ did: "did:web:open-denki.etzhayyim.com" });
  });

  describe("defineGenerationNode", () => {
    it("defines a generation node with valid input", async () => {
      const result = await defineGenerationNode(e, {
        nodeId: "solar-001",
        name: "Riverside Solar Farm",
        kind: "solar",
        capacityKw: 50_000,
        voltageLevel: "hv",
      });

      expect(result.status).toBe("registered");
      expect(result.generationNodeUri).toBeDefined();
      expect(result.did).toBeDefined();
      expect(result.nodeId).toBe("solar-001");
    });

    it("rejects generation node with invalid capacityKw", async () => {
      const result = await defineGenerationNode(e, {
        nodeId: "gen-001",
        name: "Bad Gen",
        kind: "wind",
        capacityKw: 0,
      });

      expect(result.status).toBe("rejected");
      expect(result.error).toBe("missingOrInvalidFields");
    });

    it("is idempotent on nodeId", async () => {
      const input = {
        nodeId: "hydro-001",
        name: "Mountain Hydro",
        kind: "hydro" as const,
        capacityKw: 100_000,
      };

      const first = await defineGenerationNode(e, input);
      expect(first.status).toBe("registered");
      const firstUri = first.generationNodeUri;

      const second = await defineGenerationNode(e, input);
      expect(second.status).toBe("alreadyExists");
      expect(second.generationNodeUri).toBe(firstUri);
    });
  });

  describe("defineSubstation", () => {
    it("defines a substation with valid input", async () => {
      const result = await defineSubstation(e, {
        substationId: "sub-01",
        name: "Downtown Substation",
        kind: "distribution",
        primaryVoltageKv: 132,
        secondaryVoltageKv: 33,
        capacityMva: 100,
      });

      expect(result.status).toBe("registered");
      expect(result.substationUri).toBeDefined();
      expect(result.did).toBeDefined();
      expect(result.substationId).toBe("sub-01");
    });

    it("rejects substation with missing numeric fields", async () => {
      const result = await defineSubstation(e, {
        substationId: "sub-02",
        name: "Bad Sub",
        kind: "transmission",
        primaryVoltageKv: 220,
        secondaryVoltageKv: undefined as any,
        capacityMva: 50,
      });

      expect(result.status).toBe("rejected");
    });

    it("is idempotent on substationId", async () => {
      const input = {
        substationId: "sub-dup",
        name: "Duplicate Substation",
        kind: "distribution" as const,
        primaryVoltageKv: 110,
        secondaryVoltageKv: 22,
        capacityMva: 80,
      };

      const first = await defineSubstation(e, input);
      expect(first.status).toBe("registered");
      const firstUri = first.substationUri;

      const second = await defineSubstation(e, input);
      expect(second.status).toBe("alreadyExists");
      expect(second.substationUri).toBe(firstUri);
    });
  });

  describe("defineFeeder", () => {
    beforeEach(async () => {
      await defineSubstation(e, {
        substationId: "sub-parent",
        name: "Parent Substation",
        kind: "distribution",
        primaryVoltageKv: 132,
        secondaryVoltageKv: 33,
        capacityMva: 100,
      });
    });

    it("defines a feeder requiring existing substation", async () => {
      const result = await defineFeeder(e, {
        feederId: "feeder-001",
        name: "Main Feeder 1",
        substationId: "sub-parent",
        voltageLevel: "mv",
        customerCount: 500,
      });

      expect(result.status).toBe("registered");
      expect(result.feederUri).toBeDefined();
      expect(result.feederId).toBe("feeder-001");
    });

    it("returns substationNotFound when parent substation missing", async () => {
      const result = await defineFeeder(e, {
        feederId: "feeder-orphan",
        name: "Orphan Feeder",
        substationId: "nonexistent-sub",
        voltageLevel: "lv",
        customerCount: 100,
      });

      expect(result.status).toBe("substationNotFound");
    });

    it("is idempotent on feederId", async () => {
      const input = {
        feederId: "feeder-dup",
        name: "Duplicate Feeder",
        substationId: "sub-parent",
        voltageLevel: "lv" as const,
        customerCount: 200,
      };

      const first = await defineFeeder(e, input);
      expect(first.status).toBe("registered");
      const firstUri = first.feederUri;

      const second = await defineFeeder(e, input);
      expect(second.status).toBe("alreadyExists");
      expect(second.feederUri).toBe(firstUri);
    });
  });

  describe("registerSmartMeter", () => {
    beforeEach(async () => {
      await defineSubstation(e, {
        substationId: "sub-meters",
        name: "Meter Substation",
        kind: "distribution",
        primaryVoltageKv: 110,
        secondaryVoltageKv: 22,
        capacityMva: 80,
      });
      await defineFeeder(e, {
        feederId: "feeder-meters",
        name: "Meter Feeder",
        substationId: "sub-meters",
        voltageLevel: "lv",
        customerCount: 300,
      });
    });

    it("registers a smart meter with feeder", async () => {
      const result = await registerSmartMeter(e, {
        meterId: "meter-001",
        kind: "consumption",
        feederId: "feeder-meters",
      });

      expect(result.status).toBe("registered");
      expect(result.smartMeterUri).toBeDefined();
      expect(result.meterId).toBe("meter-001");
    });

    it("returns feederNotFound when feeder missing", async () => {
      const result = await registerSmartMeter(e, {
        meterId: "meter-orphan",
        kind: "consumption",
        feederId: "nonexistent-feeder",
      });

      expect(result.status).toBe("feederNotFound");
    });

    it("is idempotent on meterId", async () => {
      const input = {
        meterId: "meter-dup",
        kind: "consumption" as const,
        feederId: "feeder-meters",
      };

      const first = await registerSmartMeter(e, input);
      expect(first.status).toBe("registered");
      const firstUri = first.smartMeterUri;

      const second = await registerSmartMeter(e, input);
      expect(second.status).toBe("alreadyExists");
      expect(second.smartMeterUri).toBe(firstUri);
    });
  });

  describe("recordMeterReading", () => {
    beforeEach(async () => {
      await defineSubstation(e, {
        substationId: "sub-reading",
        name: "Reading Substation",
        kind: "distribution",
        primaryVoltageKv: 110,
        secondaryVoltageKv: 22,
        capacityMva: 80,
      });
      await defineFeeder(e, {
        feederId: "feeder-reading",
        name: "Reading Feeder",
        substationId: "sub-reading",
        voltageLevel: "lv",
        customerCount: 200,
      });
      await registerSmartMeter(e, {
        meterId: "meter-test",
        kind: "consumption",
        feederId: "feeder-reading",
      });
    });

    it("records a meter reading with monotonic validation for consumption", async () => {
      const result = await recordMeterReading(e, {
        meterId: "meter-test",
        readingSeq: 1,
        kwhCumulative: 1000,
        readAt: "2026-05-21T10:00:00Z",
      });

      expect(result.status).toBe("recorded");
      expect(result.readingUri).toBeDefined();
      expect(result.readingSeq).toBe(1);
    });

    it("rejects meter reading with nonMonotonic kWh regression for consumption meter", async () => {
      await recordMeterReading(e, {
        meterId: "meter-test",
        readingSeq: 1,
        kwhCumulative: 1000,
        readAt: "2026-05-21T10:00:00Z",
      });

      const result = await recordMeterReading(e, {
        meterId: "meter-test",
        readingSeq: 2,
        kwhCumulative: 950, // Regressed
        readAt: "2026-05-21T11:00:00Z",
      });

      expect(result.status).toBe("nonMonotonic");
      expect(result.error).toBe("kwhRegression");
    });

    it("accepts increasing kWh readings for consumption meter", async () => {
      await recordMeterReading(e, {
        meterId: "meter-test",
        readingSeq: 1,
        kwhCumulative: 1000,
        readAt: "2026-05-21T10:00:00Z",
      });

      const result = await recordMeterReading(e, {
        meterId: "meter-test",
        readingSeq: 2,
        kwhCumulative: 1050, // Increased
        readAt: "2026-05-21T11:00:00Z",
      });

      expect(result.status).toBe("recorded");
    });

    it("returns meterNotFound for missing meter", async () => {
      const result = await recordMeterReading(e, {
        meterId: "nonexistent-meter",
        readingSeq: 1,
        kwhCumulative: 100,
        readAt: "2026-05-21T10:00:00Z",
      });

      expect(result.status).toBe("rejected");
      expect(result.error).toBe("meterNotFound");
    });

    it("is idempotent on readingSeq", async () => {
      const input = {
        meterId: "meter-test",
        readingSeq: 5,
        kwhCumulative: 5000,
        readAt: "2026-05-21T15:00:00Z",
      };

      const first = await recordMeterReading(e, input);
      expect(first.status).toBe("recorded");
      const firstUri = first.readingUri;

      const second = await recordMeterReading(e, input);
      expect(second.status).toBe("alreadyExists");
      expect(second.readingUri).toBe(firstUri);
    });
  });

  describe("getNode", () => {
    beforeEach(async () => {
      await defineGenerationNode(e, {
        nodeId: "gen-get",
        name: "Getable Gen",
        kind: "wind",
        capacityKw: 75_000,
      });
      await defineSubstation(e, {
        substationId: "sub-get",
        name: "Getable Sub",
        kind: "transmission",
        primaryVoltageKv: 220,
        secondaryVoltageKv: 110,
        capacityMva: 150,
      });
    });

    it("retrieves a generation node", async () => {
      const result = await getNode(e, { nodeId: "gen-get" });

      expect(result.error).toBeUndefined();
      expect(result.node).toBeDefined();
      expect(result.node?.name).toBe("Getable Gen");
    });

    it("retrieves a substation", async () => {
      const result = await getNode(e, { nodeId: "sub-get" });

      expect(result.error).toBeUndefined();
      expect(result.node).toBeDefined();
      expect(result.node?.name).toBe("Getable Sub");
    });

    it("returns notFound for missing node", async () => {
      const result = await getNode(e, { nodeId: "nonexistent-node" });

      expect(result.error).toBe("notFound");
      expect(result.node).toBeUndefined();
    });
  });
});
