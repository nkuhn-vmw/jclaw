-- V5__scheduled_tasks.sql

CREATE TABLE scheduled_tasks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(256) NOT NULL,
    cron_expression VARCHAR(128) NOT NULL,
    message         VARCHAR(1000),
    agent_id        VARCHAR(64),
    principal       VARCHAR(256),
    next_fire_at    TIMESTAMPTZ,
    last_fired_at   TIMESTAMPTZ,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_scheduled_tasks_status ON scheduled_tasks(status, next_fire_at);
