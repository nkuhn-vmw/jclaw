-- V3__identity_mappings.sql

CREATE TABLE identity_mappings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_type    VARCHAR(32) NOT NULL,
    channel_user_id VARCHAR(256) NOT NULL,
    jclaw_principal VARCHAR(256) NOT NULL,
    display_name    VARCHAR(256),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ,
    approved        BOOLEAN NOT NULL DEFAULT false,
    approved_by     VARCHAR(256),
    UNIQUE(channel_type, channel_user_id)
);
