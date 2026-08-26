package com.uniton.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FakeAdapterContractTest {

    @Test
    void recordsSlackNotionAndLmStudioCallsForInspection() {
        // Given
        var adapters = new FakeAdapters();
        var harness = new FakeAdapterContractHarness(
                adapters,
                new DeterministicTestValues(java.time.Instant.parse("2026-08-26T00:00:00Z"), 41L));

        // When
        var slackReceipt = harness.execute(
                new FakeAdapterContractHarness.SlackPublish("project-1", "channel-1", "status-card"));
        harness.execute(new FakeAdapterContractHarness.NotionCreateChildPage(
                "project-1", "parent-1", "snapshot"));
        harness.execute(new FakeAdapterContractHarness.LmGenerate("project-1", "plan-draft"));

        // Then
        assertThat(adapters.slack().calls()).containsExactly(
                new FakeAdapters.SlackCall("project-1", "channel-1", "status-card"));
        assertThat(adapters.notion().calls()).containsExactly(
                new FakeAdapters.NotionCall("project-1", "parent-1", "snapshot"));
        assertThat(adapters.lmStudio().calls()).containsExactly(
                new FakeAdapters.LmStudioCall("project-1", "plan-draft"));
        assertThat(slackReceipt.id()).isEqualTo("test-id-41");
        assertThat(slackReceipt.recordedAt()).isEqualTo(java.time.Instant.parse("2026-08-26T00:00:00Z"));
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
    void interruptedLmStudioCallThroughHarnessFailsWithoutRecordingSuccess() {
        // Given
        var adapters = new FakeAdapters();
        var values = new DeterministicTestValues(java.time.Instant.parse("2026-08-26T00:00:00Z"), 1L);
        var harness = new FakeAdapterContractHarness(adapters, values);
        Thread.currentThread().interrupt();

        try {
            // When / Then
            assertThatThrownBy(() -> harness.execute(
                            new FakeAdapterContractHarness.LmGenerate("project-1", "plan-draft")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("interrupted");
            assertThat(adapters.lmStudio().calls()).isEmpty();
        } finally {
            Thread.interrupted();
        }
        assertThat(harness.execute(new FakeAdapterContractHarness.SlackPublish(
                        "project-1", "channel-1", "status-card")).id())
                .isEqualTo("test-id-1");
    }
}
