-- V1__initial_schema.sql

CREATE TABLE sessions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id                VARCHAR(64) NOT NULL,
    channel_type            VARCHAR(32) NOT NULL,
    channel_conversation_id VARCHAR(256),
    principal               VARCHAR(256) NOT NULL,
    scope                   VARCHAR(16) NOT NULL DEFAULT 'MAIN',
    metadata                JSONB DEFAULT '{}',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_active_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    message_count           INT NOT NULL DEFAULT 0,
    total_tokens            INT NOT NULL DEFAULT 0,
    status                  VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
);

CREATE INDEX idx_sessions_principal ON sessions(principal, status);
CREATE INDEX idx_sessions_agent ON sessions(agent_id, status);
CREATE INDEX idx_sessions_channel ON sessions(channel_type, channel_conversation_id);

CREATE TABLE session_messages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    role            VARCHAR(16) NOT NULL,
    content         TEXT NOT NULL,
    token_count     INT,
    tool_calls      JSONB,
    tool_results    JSONB,
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_compacted    BOOLEAN NOT NULL DEFAULT false
);

CREATE INDEX idx_messages_session ON session_messages(session_id, created_at);

CREATE TABLE agent_configs (
    agent_id            VARCHAR(64) PRIMARY KEY,
    display_name        VARCHAR(256),
    model               VARCHAR(128),
    trust_level         VARCHAR(16) NOT NULL DEFAULT 'STANDARD',
    system_prompt       TEXT,
    allowed_tools       TEXT[],
    denied_tools        TEXT[],
    egress_allowlist    TEXT[],
    max_tokens_per_request INT DEFAULT 4096,
    max_tool_calls      INT DEFAULT 10,
    max_history_tokens  INT DEFAULT 128000,
    config_json         JSONB DEFAULT '{}',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
