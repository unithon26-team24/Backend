package com.uniton.backend.support;

import java.time.Instant;

public final class FakeAdapterContractHarness {

    private final FakeAdapters adapters;
    private final DeterministicTestValues values;

    public FakeAdapterContractHarness(FakeAdapters adapters, DeterministicTestValues values) {
        this.adapters = adapters;
        this.values = values;
    }

    public Receipt execute(Operation operation) {
        switch (operation) {
            case SlackPublish call -> adapters.slack()
                    .publish(call.projectId(), call.channelId(), call.contentRef());
            case NotionCreateChildPage call -> adapters.notion()
                    .createChildPage(call.projectId(), call.parentPageId(), call.contentRef());
            case LmGenerate call -> adapters.lmStudio()
                    .generate(call.projectId(), call.requestRef());
        }
        return new Receipt(values.nextId(), values.clock().instant());
    }

    public Receipt execute(String operation) {
        throw new IllegalArgumentException("undeclared fake operation: " + operation);
    }

    public sealed interface Operation
            permits SlackPublish, NotionCreateChildPage, LmGenerate {}

    public record SlackPublish(String projectId, String channelId, String contentRef)
            implements Operation {}

    public record NotionCreateChildPage(String projectId, String parentPageId, String contentRef)
            implements Operation {}

    public record LmGenerate(String projectId, String requestRef) implements Operation {}

    public record Receipt(String id, Instant recordedAt) {}
}
