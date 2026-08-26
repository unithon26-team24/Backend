package com.uniton.backend.support;

import java.util.ArrayList;
import java.util.List;

public final class FakeAdapters {

    private final Slack slack = new Slack();
    private final Notion notion = new Notion();
    private final LmStudio lmStudio = new LmStudio();

    public Slack slack() {
        return slack;
    }

    public Notion notion() {
        return notion;
    }

    public LmStudio lmStudio() {
        return lmStudio;
    }

    public static final class Slack {
        private final List<SlackCall> calls = new ArrayList<>();

        public void publish(String projectId, String channelId, String contentRef) {
            calls.add(new SlackCall(projectId, channelId, contentRef));
        }

        public List<SlackCall> calls() {
            return List.copyOf(calls);
        }
    }

    public static final class Notion {
        private final List<NotionCall> calls = new ArrayList<>();

        public void createChildPage(String projectId, String parentPageId, String contentRef) {
            calls.add(new NotionCall(projectId, parentPageId, contentRef));
        }

        public List<NotionCall> calls() {
            return List.copyOf(calls);
        }
    }

    public static final class LmStudio {
        private final List<LmStudioCall> calls = new ArrayList<>();

        public void generate(String projectId, String requestRef) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("fake LM Studio call interrupted");
            }
            calls.add(new LmStudioCall(projectId, requestRef));
        }

        public List<LmStudioCall> calls() {
            return List.copyOf(calls);
        }
    }

    public record SlackCall(String projectId, String channelId, String contentRef) {}

    public record NotionCall(String projectId, String parentPageId, String contentRef) {}

    public record LmStudioCall(String projectId, String requestRef) {}
}
