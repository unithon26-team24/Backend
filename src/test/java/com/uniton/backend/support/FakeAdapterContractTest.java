package com.uniton.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FakeAdapterContractTest {

    @Test
    void recordsSlackNotionAndLmStudioCallsForInspection() {
        // Given
        var adapters = new FakeAdapters();

        // When
        adapters.slack().publish("project-1", "channel-1", "status-card");
        adapters.notion().createChildPage("project-1", "parent-1", "snapshot");
        adapters.lmStudio().generate("project-1", "plan-draft");

        // Then
        assertThat(adapters.slack().calls()).containsExactly(
                new FakeAdapters.SlackCall("project-1", "channel-1", "status-card"));
        assertThat(adapters.notion().calls()).containsExactly(
                new FakeAdapters.NotionCall("project-1", "parent-1", "snapshot"));
        assertThat(adapters.lmStudio().calls()).containsExactly(
                new FakeAdapters.LmStudioCall("project-1", "plan-draft"));
    }

    @Test
    void startsEveryFakeAdapterSetWithFreshState() {
        // Given
        var first = new FakeAdapters();
        first.slack().publish("project-1", "channel-1", "status-card");

        // When
        var second = new FakeAdapters();

        // Then
        assertThat(second.slack().calls()).isEmpty();
        assertThat(second.notion().calls()).isEmpty();
        assertThat(second.lmStudio().calls()).isEmpty();
    }

    @Test
    void rejectsUndeclaredFakeOperationWithoutRecordingCall() {
        // Given
        var adapters = new FakeAdapters();

        // When / Then
        assertThatThrownBy(() -> adapters.invoke("slack", "delete_workspace", "project-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("undeclared fake operation");
        assertThat(adapters.slack().calls()).isEmpty();
    }

    @Test
    void interruptedLmStudioCallFailsWithoutRecordingSuccess() {
        // Given
        var adapters = new FakeAdapters();
        Thread.currentThread().interrupt();

        try {
            // When / Then
            assertThatThrownBy(() -> adapters.lmStudio().generate("project-1", "plan-draft"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("interrupted");
            assertThat(adapters.lmStudio().calls()).isEmpty();
        } finally {
            Thread.interrupted();
        }
    }
}
