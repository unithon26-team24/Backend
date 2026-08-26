package com.uniton.backend.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

public final class DeterministicTestValues {

    private final Clock clock;
    private final AtomicLong nextId;

    public DeterministicTestValues(Instant instant, long firstId) {
        clock = Clock.fixed(instant, ZoneOffset.UTC);
        nextId = new AtomicLong(firstId);
    }

    public Clock clock() {
        return clock;
    }

    public String nextId() {
        return "test-id-" + nextId.getAndIncrement();
    }
}
