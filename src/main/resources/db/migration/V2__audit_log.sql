-- V2__audit_log.sql

CREATE TABLE audit_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    timestamp       TIMESTAMPTZ NOT NULL DEFAULT now(),
    event_type      VARCHAR(64) NOT NULL,
    principal       VARCHAR(256),
    agent_id        VARCHAR(64),
    session_id      UUID,
    channel_type    VARCHAR(32),
    action          VARCHAR(256) NOT NULL,
    resource_type   VARCHAR(64),
    resource_id     VARCHAR(256),
    details         JSONB DEFAULT '{}',
    source_ip       VARCHAR(45),
    outcome         VARCHAR(16) NOT NULL DEFAULT 'SUCCESS'
);

CREATE INDEX idx_audit_timestamp ON audit_log(timestamp DESC);
CREATE INDEX idx_audit_principal ON audit_log(principal, timestamp DESC);
CREATE INDEX idx_audit_event_type ON audit_log(event_type, timestamp DESC);
