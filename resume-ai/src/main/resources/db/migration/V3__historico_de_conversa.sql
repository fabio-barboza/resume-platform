CREATE TABLE IF NOT EXISTS chat_messages (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    session_id      TEXT NOT NULL,
    message_type    TEXT NOT NULL,
    content         TEXT NOT NULL DEFAULT '',
    tool_calls      JSONB,
    tool_responses  JSONB,
    grounding_retry BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL,
    CONSTRAINT chat_messages_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS chat_messages_session_id_idx ON chat_messages (session_id, id);
