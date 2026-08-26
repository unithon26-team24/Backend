import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import test from "node:test";
import {
  SpringFakeBoundary,
  createRecordedPrivateBoundary,
} from "../test-support/recorded-private-boundary.js";

const fixtureUrl = new URL("./fixtures/adapter-boundary-contract.json", import.meta.url);
const fixtures = JSON.parse(await readFile(fixtureUrl, "utf8"));
const canonicalContractUrl = new URL("../../../contracts/slack/private-command-contract.json", import.meta.url);
const canonicalContract = JSON.parse(await readFile(canonicalContractUrl, "utf8"));
const recordedBoundary = createRecordedPrivateBoundary(canonicalContract);

test("recorded boundary accepts normalized event interaction immediate-ui and publish-result schemas", () => {
  // Given
  // When
  const serialized = [
    recordedBoundary.serializeCommand("SlackEventCmd", fixtures.event),
    recordedBoundary.serializeCommand("SlackInteractionCmd", fixtures.interaction),
    recordedBoundary.serializeImmediateUi(fixtures.immediate_ui),
    recordedBoundary.serializePublishResult(fixtures.publish_result),
  ];

  // Then
  assert.equal(serialized.length, 4);
  assert.equal(serialized.every((value) => !value.includes("trigger_id")), true);
  assert.doesNotThrow(() => recordedBoundary.serializeImmediateUi({kind: "ephemeral", text: "normalized text"}));
});

test("durable fixture rejects trigger_id as a private command field at every depth", () => {
  // Given
  const hostileFixtures = [
    {...fixtures.publish_result, trigger_id: "hostile-short-lived-value"},
    {...fixtures.publish_result, result: {...fixtures.publish_result.result, trigger_id: "hostile-short-lived-value"}},
  ];

  // When / Then
  hostileFixtures.forEach((fixture) => {
    assert.throws(() => recordedBoundary.serializePublishResult(fixture), /trigger_id/);
  });
});

test("every canonical forbidden field is rejected inside allowed immediate-ui content", () => {
  // Given
  const hostileFixtures = canonicalContract.forbidden_fields.map((field) => ({
    kind: "ephemeral",
    text: "normalized text",
    blocks: [{type: "section", [field]: "hostile-value"}],
  }));

  // When / Then
  hostileFixtures.forEach((fixture) => {
    assert.throws(() => recordedBoundary.serializeImmediateUi(fixture), /forbids/);
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
    const serialize = Object.hasOwn(fixture, "provider_event_id")
      ? () => recordedBoundary.serializeCommand("SlackEventCmd", fixture)
      : () => recordedBoundary.serializePublishResult(fixture);
    assert.throws(serialize, /not declared|not normalized/);
  });
});

test("bounded contract fixture records no Spring fake command for legacy or unauthenticated input", () => {
  // Given
  const springBoundary = new SpringFakeBoundary();
  const rejectedRequests = fixtures.rejected_requests;

  // When
  const results = rejectedRequests.map((request) => recordedBoundary.forward(
    {...request, body: fixtures[request.body_fixture]},
    springBoundary,
  ));

  // Then
  assert.deepEqual(results.map(({disposition}) => disposition), ["legacy-route", "identity-rejected", "identity-rejected"]);
  assert.deepEqual(springBoundary.commands(), []);
});

test("bounded contract fixture records no Spring fake command for canonical forbidden content", () => {
  // Given
  const springBoundary = new SpringFakeBoundary();
  const body = {
    ...fixtures.event,
    message: {...fixtures.event.message, raw_event: {event_id: "hostile-event"}},
  };

  // When / Then
  assert.throws(() => recordedBoundary.forward(
    {path: "/internal/slack/events", valid_private_identity: true, body},
    springBoundary,
  ), /forbids raw_event/);
  assert.deepEqual(springBoundary.commands(), []);
});

test("bounded contract fixture forwards normalized command to injected Spring fake boundary", () => {
  // Given
  const springBoundary = new SpringFakeBoundary();
  const request = fixtures.valid_private_request;

  // When
  const result = recordedBoundary.forward(
    {...request, body: fixtures[request.body_fixture]},
    springBoundary,
  );

  // Then
  assert.deepEqual(result, {disposition: "forwarded"});
  assert.deepEqual(springBoundary.commands(), [{name: "SlackEventCmd", body: fixtures.event}]);
});
