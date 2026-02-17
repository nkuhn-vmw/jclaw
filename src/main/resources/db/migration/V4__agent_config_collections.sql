-- V4__agent_config_collections.sql
-- Migrate agent_configs from TEXT[] columns to proper join tables for @ElementCollection

-- Create join tables for agent tool and egress collections
CREATE TABLE agent_allowed_tools (
    agent_id    VARCHAR(64) NOT NULL REFERENCES agent_configs(agent_id) ON DELETE CASCADE,
    tool_name   VARCHAR(128) NOT NULL,
    PRIMARY KEY (agent_id, tool_name)
);

CREATE TABLE agent_denied_tools (
    agent_id    VARCHAR(64) NOT NULL REFERENCES agent_configs(agent_id) ON DELETE CASCADE,
    tool_name   VARCHAR(128) NOT NULL,
    PRIMARY KEY (agent_id, tool_name)
);

CREATE TABLE agent_egress_allowlist (
    agent_id    VARCHAR(64) NOT NULL REFERENCES agent_configs(agent_id) ON DELETE CASCADE,
    pattern     VARCHAR(256) NOT NULL,
    PRIMARY KEY (agent_id, pattern)
);

-- Migrate existing data from TEXT[] columns to join tables
INSERT INTO agent_allowed_tools (agent_id, tool_name)
SELECT agent_id, unnest(allowed_tools) FROM agent_configs WHERE allowed_tools IS NOT NULL;

INSERT INTO agent_denied_tools (agent_id, tool_name)
SELECT agent_id, unnest(denied_tools) FROM agent_configs WHERE denied_tools IS NOT NULL;

INSERT INTO agent_egress_allowlist (agent_id, pattern)
SELECT agent_id, unnest(egress_allowlist) FROM agent_configs WHERE egress_allowlist IS NOT NULL;

-- Drop old TEXT[] columns
ALTER TABLE agent_configs DROP COLUMN IF EXISTS allowed_tools;
ALTER TABLE agent_configs DROP COLUMN IF EXISTS denied_tools;
ALTER TABLE agent_configs DROP COLUMN IF EXISTS egress_allowlist;

-- Add ContentFilterPolicy columns
ALTER TABLE agent_configs ADD COLUMN IF NOT EXISTS max_message_length INT DEFAULT 50000;
ALTER TABLE agent_configs ADD COLUMN IF NOT EXISTS enable_pattern_detection BOOLEAN DEFAULT true;
ALTER TABLE agent_configs ADD COLUMN IF NOT EXISTS enable_instruction_detection BOOLEAN DEFAULT true;
ALTER TABLE agent_configs ADD COLUMN IF NOT EXISTS enable_egress_guard BOOLEAN DEFAULT true;
