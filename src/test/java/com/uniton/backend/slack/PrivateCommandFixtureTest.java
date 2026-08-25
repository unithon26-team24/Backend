package com.uniton.backend.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PrivateCommandFixtureTest {

    private static JsonNode contract;
    private static Set<String> forbiddenFields;

    @BeforeAll
    static void loadContract() throws IOException {
        var mapper = new ObjectMapper();
        var contractPath = Path.of("contracts/slack/private-command-contract.json");
        contract = mapper.readTree(Files.readString(contractPath));
        forbiddenFields = stringSet(contract.get("forbidden_fields"));
    }

    @Test
    void acceptsAllThreeClosedNormalizedCommandFixtures() {
        // Given: final normalized fixtures for event, interaction, and publish commands.
        var commands = contract.get("commands");

        // When: each fixture is checked against its command and type-specific field sets.
        commands.properties().forEach(entry -> validateCommand(entry.getKey(), entry.getValue()));

        // Then: the fixture catalog contains exactly the three private command identities.
        assertThat(fieldSet(commands)).containsExactlyInAnyOrder(
                "SlackEventCmd", "SlackInteractionCmd", "SlackPublishCmd");
    }

    @Test
    void rejectsUnknownAndSensitiveEnvelopeFields() {
        // Given: each forbidden envelope, secret, and short-lived field is injected into a fixture.
        var eventRule = contract.path("commands").path("SlackEventCmd");

        // When/Then: every injected field is rejected at the closed boundary.
        forbiddenFields.forEach(field -> {
            var mutated = (ObjectNode) eventRule.get("fixture").deepCopy();
            mutated.put(field, "must-not-cross-boundary");
            assertThatThrownBy(() -> validateCommandFixture("SlackEventCmd", eventRule, mutated))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void enumeratesEveryClosedImmediateUiVariant() {
        // Given: the bounded immediate UI contract.
        var immediateUi = contract.get("immediate_ui_fields");

        // When: its discriminants are enumerated.
        var kinds = fieldSet(immediateUi);

        // Then: no kind outside the final seven-kind contract is present.
        assertThat(kinds).containsExactlyInAnyOrder(
                "none", "open_modal", "update_modal", "push_modal",
                "modal_errors", "options", "ephemeral");
        immediateUi.properties().forEach(entry -> {
            var required = stringSet(entry.getValue().get("required"));
            var optional = stringSet(entry.getValue().get("optional"));
            assertThat(required).contains("kind");
            assertThat(required).doesNotContainAnyElementsOf(forbiddenFields);
            assertThat(optional).doesNotContainAnyElementsOf(forbiddenFields);
        });
    }

    private static void validateCommand(String name, JsonNode rule) {
        validateCommandFixture(name, rule, rule.get("fixture"));
    }

    private static void validateCommandFixture(String name, JsonNode rule, JsonNode fixture) {
        rejectForbiddenRecursively(fixture);
        var typeField = rule.get("type_field").asText();
        var type = fixture.get(typeField).asText();
        var typeFields = rule.path("type_fields").get(type);
        if (typeFields == null) {
            throw new IllegalArgumentException(name + " has unknown type: " + type);
        }
        var allowed = stringSet(rule.get("common_fields"));
        allowed.addAll(stringSet(typeFields));
        assertExactFields(name, fixture, allowed);

        rule.path("objects").properties().forEach(entry -> {
            if (fixture.has(entry.getKey())) {
                assertExactFields(name + "." + entry.getKey(), fixture.get(entry.getKey()), stringSet(entry.getValue()));
            }
        });
        if (rule.has("target_fields")) {
            assertExactFields(name + ".target", fixture.get("target"),
                    stringSet(rule.get("target_fields").get(type)));
        }
    }

    private static void rejectForbiddenRecursively(JsonNode node) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (forbiddenFields.contains(entry.getKey())) {
                    throw new IllegalArgumentException("forbidden field: " + entry.getKey());
                }
                rejectForbiddenRecursively(entry.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(PrivateCommandFixtureTest::rejectForbiddenRecursively);
        }
    }

    private static void assertExactFields(String label, JsonNode node, Set<String> allowed) {
        if (!node.isObject() || !allowed.equals(fieldSet(node))) {
            throw new IllegalArgumentException(label + " fields must equal " + allowed);
        }
    }

    private static Set<String> fieldSet(JsonNode object) {
        var fields = new HashSet<String>();
        object.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static Set<String> stringSet(JsonNode array) {
        var values = new HashSet<String>();
        Iterator<JsonNode> elements = array.elements();
        elements.forEachRemaining(element -> values.add(element.asText()));
        return values;
    }
}
