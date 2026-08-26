import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import test from "node:test";
import {invokeRecordedRequest, serializeDurableFixture} from "../test-support/recorded-private-boundary.js";

const fixtureUrl = new URL("./fixtures/adapter-boundary-contract.json", import.meta.url);
const fixtures = JSON.parse(await readFile(fixtureUrl, "utf8"));

test("recorded boundary accepts normalized event interaction immediate-ui and publish-result schemas", () => {
  // Given
  const cases = [fixtures.event, fixtures.interaction, fixtures.immediate_ui, fixtures.publish_result];

  // When
  const serialized = cases.map((fixture) => serializeDurableFixture(fixture));

  // Then
  assert.equal(serialized.length, 4);
  assert.equal(serialized.every((value) => !value.includes("trigger_id")), true);
  assert.doesNotThrow(() => serializeDurableFixture({kind: "ephemeral", text: "normalized text"}));
});

test("durable fixture rejects trigger_id as a private command field at every depth", () => {
  // Given
  const hostileFixtures = [
    {...fixtures.publish_result, trigger_id: "hostile-short-lived-value"},
    {...fixtures.publish_result, result: {...fixtures.publish_result.result, trigger_id: "hostile-short-lived-value"}},
  ];

  // When / Then
  hostileFixtures.forEach((fixture) => {
    assert.throws(() => serializeDurableFixture(fixture), /trigger_id/);
  });
});

test("durable fixture rejects malformed operation and unknown schema fields", () => {
  // Given
  const malformedFixtures = [
    {...fixtures.publish_result, operation: "delete_workspace"},
    {...fixtures.event, unknown_field: "boundary escape"},
  ];

  // When / Then
  malformedFixtures.forEach((fixture) => {
    assert.throws(() => serializeDurableFixture(fixture), /not declared|not normalized/);
  });
});

test("legacy public webhook and unauthenticated private requests cannot reach Spring commands", () => {
  // Given
  const rejectedRequests = fixtures.rejected_requests;

  // When
  const results = rejectedRequests.map((request) => invokeRecordedRequest(request));

  // Then
  assert.deepEqual(results.map(({status}) => status), [404, 401, 401]);
  assert.equal(results.every(({reached_private_command}) => reached_private_command === false), true);
});

test("valid private identity reaches only declared normalized private routes", () => {
  // Given
  const request = fixtures.valid_private_request;

  // When
  const result = invokeRecordedRequest(request);

  // Then
  assert.deepEqual(result, {status: 202, reached_private_command: true});
});
