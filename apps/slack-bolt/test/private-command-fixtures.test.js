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
    kind: "fixture-kind",
    options: [],
    text: "normalized text",
    view: {type: "modal"},
  };
  for (const {required, optional} of Object.values(fields)) {
    assert.equal(required.includes("kind"), true);
    assert.equal(required.some((field) => forbiddenFields.has(field)), false);
    assert.equal(optional.some((field) => forbiddenFields.has(field)), false);
    const allowed = new Set([...required, ...optional]);
    const fixture = Object.fromEntries([...allowed].map((field) => [field, valueByField[field]]));
    exactFields(fixture, allowed, "immediate_ui");
    assert.throws(() => exactFields({...fixture, trigger_id: "blocked"}, allowed, "immediate_ui"));
    const requiredFixture = Object.fromEntries(required.map((field) => [field, valueByField[field]]));
    exactFields(requiredFixture, new Set(required), "immediate_ui required");
  }
});
