const forbiddenFields = new Set([
  "app_token",
  "authorization",
  "bot_token",
  "credential",
  "envelope",
  "payload",
  "raw_envelope",
  "raw_event_payload",
  "signature",
  "token",
  "trigger_id",
]);

const privateRoutes = new Map([
  ["/internal/slack/events", "event"],
  ["/internal/slack/interactions", "interaction"],
  ["/internal/slack/publish-commands", "publish_command"],
]);

function rejectForbidden(value) {
  if (Array.isArray(value)) {
    value.forEach(rejectForbidden);
    return;
  }
  if (value === null || typeof value !== "object") {
    return;
  }
  for (const [field, nested] of Object.entries(value)) {
    if (forbiddenFields.has(field)) {
      throw new TypeError(`durable fixture forbids ${field}`);
    }
    rejectForbidden(nested);
  }
}

function requireExactFields(value, fields, label) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new TypeError(`${label} must be an object`);
  }
  const actual = Object.keys(value).sort();
  const expected = [...fields].sort();
  if (actual.length !== expected.length || actual.some((field, index) => field !== expected[index])) {
    throw new TypeError(`${label} fields are not normalized`);
  }
}

function requireClosedFields(value, required, optional, label) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new TypeError(`${label} must be an object`);
  }
  const actual = Object.keys(value);
  const allowed = new Set([...required, ...optional]);
  if (!required.every((field) => Object.hasOwn(value, field))
      || actual.some((field) => !allowed.has(field))) {
    throw new TypeError(`${label} fields are not normalized`);
  }
}

function validateFixture(fixture) {
  if (Object.hasOwn(fixture, "provider_event_id")) {
    requireExactFields(fixture, [
      "actor_slack_user_id", "event_subtype", "event_type", "location", "message",
      "provider_event_id", "received_at", "request_id", "team_id",
    ], "event");
    requireExactFields(fixture.location, ["channel_id", "message_ts", "thread_ts"], "event.location");
    requireExactFields(fixture.message, ["text"], "event.message");
    return;
  }
  if (Object.hasOwn(fixture, "interaction_id")) {
    const fieldsBySurface = {
      block_actions: ["action_id", "callback_id", "context_ref", "location"],
      block_suggestion: ["action_id", "callback_id", "context_ref"],
      slash_command: ["command", "context_ref", "location"],
      view_submission: ["callback_id", "context_ref", "location", "submitted_values", "view_id"],
    };
    const surfaceFields = fieldsBySurface[fixture.surface];
    if (!surfaceFields) {
      throw new TypeError("interaction surface is not declared");
    }
    requireExactFields(fixture, [
      "actor_slack_user_id", "interaction_id", "received_at", "request_id", "surface", "team_id",
      ...surfaceFields,
    ], "interaction");
    if (Object.hasOwn(fixture, "location")) {
      requireExactFields(
        fixture.location,
        ["channel_id", "message_ts", "thread_ts"],
        "interaction.location",
      );
    }
    if (Object.hasOwn(fixture, "command")) {
      requireExactFields(fixture.command, ["name", "text"], "interaction.command");
    }
    return;
  }
  if (Object.hasOwn(fixture, "kind")) {
    const fieldsByKind = {
      ephemeral: {required: ["kind", "text"], optional: ["blocks"]},
      modal_errors: {required: ["errors", "kind"], optional: []},
      none: {required: ["kind"], optional: []},
      open_modal: {required: ["kind", "view"], optional: []},
      options: {required: ["kind", "options"], optional: []},
      push_modal: {required: ["kind", "view"], optional: []},
      update_modal: {required: ["kind", "view"], optional: []},
    };
    const fieldRule = fieldsByKind[fixture.kind];
    if (!fieldRule) {
      throw new TypeError("immediate_ui kind is not declared");
    }
    requireClosedFields(fixture, fieldRule.required, fieldRule.optional, "immediate_ui");
    return;
  }
  if (Object.hasOwn(fixture, "result")) {
    const fieldsByOperation = {
      ephemeral_response: ["channel_id", "message_ts"],
      post_message: ["channel_id", "message_ts"],
      publish_home: ["user_id", "view_id"],
      update_message: ["channel_id", "message_ts"],
    };
    const resultFields = fieldsByOperation[fixture.operation];
    if (!resultFields) {
      throw new TypeError("publish_result operation is not declared");
    }
    requireExactFields(fixture, [
      "command_id", "idempotency_key", "operation", "request_id", "result",
    ], "publish_result");
    requireExactFields(fixture.result, resultFields, "publish_result.result");
    return;
  }
  throw new TypeError("fixture schema is not declared");
}

export function serializeDurableFixture(fixture) {
  rejectForbidden(fixture);
  validateFixture(fixture);
  return JSON.stringify(fixture);
}

export function invokeRecordedRequest(request) {
  const expectedFixture = privateRoutes.get(request.path);
  if (!expectedFixture) {
    return {status: 404, reached_private_command: false};
  }
  if (request.valid_private_identity !== true) {
    return {status: 401, reached_private_command: false};
  }
  if (request.body_fixture !== expectedFixture) {
    return {status: 422, reached_private_command: false};
  }
  return {status: 202, reached_private_command: true};
}
