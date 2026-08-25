import assert from "node:assert/strict";
import test from "node:test";

import {buildHealth} from "../src/health.js";

test("returns Bolt bootstrap health on Node 20 or newer", () => {
  // Given: minimum supported Node runtime.
  // When: health is built.
  const health = buildHealth("20.0.0");

  // Then: stable service health is returned.
  assert.deepEqual(health, {service: "uniton-slack-bolt", status: "ok"});
});

test("rejects an unsupported Node runtime", () => {
  // Given: Node runtime below the package engine floor.
  // When/Then: bootstrap refuses to report healthy.
  assert.throws(() => buildHealth("19.9.0"), /Node\.js 20 or newer is required/);
});
