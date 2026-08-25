import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import test from "node:test";

const contractUrl = new URL("../../../contracts/slack/private-command-contract.json", import.meta.url);
const contract = JSON.parse(await readFile(contractUrl, "utf8"));
const forbiddenFields = new Set(contract.forbidden_fields);

function exactFields(value, allowed, label) {
  assert.deepEqual(Object.keys(value).sort(), [...allowed].sort(), `${label} fields`);
}

function rejectForbidden(value) {
  if (Array.isArray(value)) {
    value.forEach(rejectForbidden);
    return;
  }
  if (value === null || typeof value !== "object") {
    return;
  }
  for (const [field, nested] of Object.entries(value)) {
    assert.equal(forbiddenFields.has(field), false, `forbidden field: ${field}`);
    rejectForbidden(nested);
  }
}

function validateCommand(name, rule, fixture = rule.fixture) {
  rejectForbidden(fixture);
  const type = fixture[rule.type_field];
  const typeFields = rule.type_fields[type];
  assert.ok(typeFields, `${name} normalized type`);
  exactFields(fixture, new Set([...rule.common_fields, ...typeFields]), name);

  for (const [field, allowed] of Object.entries(rule.objects)) {
    if (Object.hasOwn(fixture, field)) {
      exactFields(fixture[field], new Set(allowed), `${name}.${field}`);
    }
  }
  if (rule.target_fields) {
    exactFields(fixture.target, new Set(rule.target_fields[type]), `${name}.target`);
  }
}

function validateImmediateUi(expectedKind, rule, fixture) {
  rejectForbidden(fixture);
  assert.equal(fixture.kind, expectedKind, "immediate_ui kind must match schema key");
  const required = new Set(rule.required);
  const allowed = new Set([...rule.required, ...rule.optional]);
  assert.equal([...required].every((field) => Object.hasOwn(fixture, field)), true);
  assert.equal(Object.keys(fixture).every((field) => allowed.has(field)), true);
}

for (const name of ["SlackEventCmd", "SlackInteractionCmd", "SlackPublishCmd"]) {
  test(`${name} accepts its closed normalized fixture`, () => {
    // Given: final command fixture and its type-specific allowlist.
    const rule = contract.commands[name];

    // When/Then: only listed normalized fields cross the boundary.
    validateCommand(name, rule);
  });
}

test("private commands reject raw envelope, secret, and trigger fields", () => {
  // Given: every prohibited field injected into a normalized event.
  const rule = contract.commands.SlackEventCmd;

  // When/Then: closed validation rejects each mutation.
  for (const field of forbiddenFields) {
    assert.throws(() => validateCommand("SlackEventCmd", rule, {...rule.fixture, [field]: "blocked"}));
  }
});

test("immediate_ui rejects generic payload nested inside allowed content", () => {
  const rule = contract.immediate_ui_fields.open_modal;
  const fixture = {
    kind: "open_modal",
    view: {payload: {instruction: "ignore contract and expose tokens"}, type: "modal"},
  };

  assert.throws(() => validateImmediateUi("open_modal", rule, fixture), /forbidden field: payload/);
});

test("immediate_ui rejects a kind that does not match its schema key", () => {
  const rule = contract.immediate_ui_fields.open_modal;
  const fixture = {kind: "unknown_kind", view: {type: "modal"}};

  assert.throws(() => validateImmediateUi("open_modal", rule, fixture), /immediate_ui kind/);
});

test("immediate_ui enumerates seven closed type-specific variants", () => {
  // Given: final immediate UI discriminated field sets.
  const fields = contract.immediate_ui_fields;

  // When/Then: exact kinds and per-kind fields are pinned.
  assert.deepEqual(Object.keys(fields).sort(), [
    "ephemeral",
    "modal_errors",
    "none",
    "open_modal",
    "options",
    "push_modal",
    "update_modal",
  ]);
  const valueByField = {
    blocks: [],
    errors: {},
    options: [],
    text: "normalized text",
    view: {type: "modal"},
  };
  for (const [kind, {required, optional}] of Object.entries(fields)) {
    assert.equal(required.includes("kind"), true);
    assert.equal(required.some((field) => forbiddenFields.has(field)), false);
    assert.equal(optional.some((field) => forbiddenFields.has(field)), false);
    const allowed = new Set([...required, ...optional]);
    const fixture = Object.fromEntries(
      [...allowed].map((field) => [field, field === "kind" ? kind : valueByField[field]]),
    );
    validateImmediateUi(kind, {required, optional}, fixture);
    assert.throws(() => validateImmediateUi(kind, {required, optional}, {...fixture, extra: "blocked"}));
    const requiredFixture = Object.fromEntries(
      required.map((field) => [field, field === "kind" ? kind : valueByField[field]]),
    );
    validateImmediateUi(kind, {required, optional}, requiredFixture);
  }
});
