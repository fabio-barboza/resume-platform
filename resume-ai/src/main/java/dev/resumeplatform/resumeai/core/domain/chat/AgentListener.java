package dev.resumeplatform.resumeai.core.domain.chat;

public interface AgentListener {
    AgentListener NONE = new AgentListener() {
    };

    default void onToolStart(String name) {
    }

    default void onToolEnd(String name) {
    }

    default void onToken(String text) {
    }

    default void onGroundingRetry() {
    }
}
