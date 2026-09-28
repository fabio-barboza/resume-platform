package dev.resumeplatform.resumeai.infra.repository.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "chat_messages")
public class ChatMessageEntity {
    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";
    public static final String TOOL = "tool";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "message_type", nullable = false)
    private String messageType;

    @Column(nullable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls")
    private String toolCalls;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_responses")
    private String toolResponses;

    @Column(name = "grounding_retry", nullable = false)
    private boolean groundingRetry;

    @Generated
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ChatMessageEntity() {
    }

    public ChatMessageEntity(String sessionId, String messageType, String content, String toolCalls,
            String toolResponses, boolean groundingRetry) {
        this.sessionId = sessionId;
        this.messageType = messageType;
        this.content = content == null ? "" : content;
        this.toolCalls = toolCalls;
        this.toolResponses = toolResponses;
        this.groundingRetry = groundingRetry;
    }

    public Long getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getMessageType() {
        return messageType;
    }

    public String getContent() {
        return content;
    }

    public String getToolCalls() {
        return toolCalls;
    }

    public String getToolResponses() {
        return toolResponses;
    }

    public boolean isGroundingRetry() {
        return groundingRetry;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
