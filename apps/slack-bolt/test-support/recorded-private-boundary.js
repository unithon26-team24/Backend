const springCommandByFixtureRoute = new Map([
  ["/internal/slack/events", "SlackEventCmd"],
  ["/internal/slack/interactions", "SlackInteractionCmd"],
]);

function requireObject(value, label) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new TypeError(`${label} must be an object`);
  }
}

function requireExactFields(value, fields, label) {
  requireObject(value, label);
  const actual = Object.keys(value).sort();
  const expected = [...fields].sort();
  if (actual.length !== expected.length || actual.some((field, index) => field !== expected[index])) {
    throw new TypeError(`${label} fields are not normalized`);
  }
}

function requireClosedFields(value, required, optional, label) {
  requireObject(value, label);
  const actual = Object.keys(value);
  const allowed = new Set([...required, ...optional]);
  if (!required.every((field) => Object.hasOwn(value, field))
      || actual.some((field) => !allowed.has(field))) {
    throw new TypeError(`${label} fields are not normalized`);
  }
}

function rejectForbidden(value, forbiddenFields) {
  if (Array.isArray(value)) {
    value.forEach((entry) => rejectForbidden(entry, forbiddenFields));
    return;
  }
  if (value === null || typeof value !== "object") {
    return;
  }
  for (const [field, nested] of Object.entries(value)) {
    if (forbiddenFields.has(field)) {
      throw new TypeError(`bounded fixture forbids ${field}`);
    }
    rejectForbidden(nested, forbiddenFields);
  }
}

function validateCommand(contract, forbiddenFields, name, fixture) {
  rejectForbidden(fixture, forbiddenFields);
  const rule = contract.commands[name];
  if (!rule) {
    throw new TypeError(`command is not declared: ${name}`);
  }
  const type = fixture[rule.type_field];
  const typeFields = rule.type_fields[type];
  if (!typeFields) {
    throw new TypeError(`${name} type is not declared`);
  }
  requireExactFields(fixture, [...rule.common_fields, ...typeFields], name);
  for (const [field, allowed] of Object.entries(rule.objects)) {
    if (Object.hasOwn(fixture, field)) {
      requireExactFields(fixture[field], allowed, `${name}.${field}`);
    }
  }
  if (rule.target_fields) {
    requireExactFields(fixture.target, rule.target_fields[type], `${name}.target`);
  }
}

function validateImmediateUi(contract, forbiddenFields, fixture) {
  rejectForbidden(fixture, forbiddenFields);
  const rule = contract.immediate_ui_fields[fixture.kind];
  if (!rule) {
    throw new TypeError("immediate_ui kind is not declared");
  }
  requireClosedFields(fixture, rule.required, rule.optional, "immediate_ui");
}

function validatePublishResult(forbiddenFields, fixture) {
  rejectForbidden(fixture, forbiddenFields);
  const resultFieldsByOperation = {
    ephemeral_response: ["channel_id", "message_ts"],
    post_message: ["channel_id", "message_ts"],
    publish_home: ["user_id", "view_id"],
    update_message: ["channel_id", "message_ts"],
  };
  const resultFields = resultFieldsByOperation[fixture.operation];
  if (!resultFields) {
    throw new TypeError("publish_result operation is not declared");
  }
  requireExactFields(
    fixture,
    ["command_id", "idempotency_key", "operation", "request_id", "result"],
    "publish_result",
  );
  requireExactFields(fixture.result, resultFields, "publish_result.result");
}

export class SpringFakeBoundary {
  #commands = [];

  record(name, body) {
    this.#commands.push({name, body: structuredClone(body)});
  }

  commands() {
    return structuredClone(this.#commands);
  }
}

export function createRecordedPrivateBoundary(contract) {
  const forbiddenFields = new Set(contract.forbidden_fields);
  return Object.freeze({
    serializeCommand(name, fixture) {
      validateCommand(contract, forbiddenFields, name, fixture);
      return JSON.stringify(fixture);
    },
    serializeImmediateUi(fixture) {
      validateImmediateUi(contract, forbiddenFields, fixture);
      return JSON.stringify(fixture);
    },
    serializePublishResult(fixture) {
      validatePublishResult(forbiddenFields, fixture);
      return JSON.stringify(fixture);
    },
    forward(request, springBoundary) {
      const commandName = springCommandByFixtureRoute.get(request.path);
      if (!commandName) {
        return {disposition: "legacy-route"};
      }
      if (request.valid_private_identity !== true) {
        return {disposition: "identity-rejected"};
      }
      validateCommand(contract, forbiddenFields, commandName, request.body);
      springBoundary.record(commandName, request.body);
      return {disposition: "forwarded"};
    },
  });
}
