-- V6__nullable_identity_principal.sql
-- Allow jclaw_principal to be null for pending identity mappings
-- that have not yet been assigned to an SSO principal by an admin.

ALTER TABLE identity_mappings ALTER COLUMN jclaw_principal DROP NOT NULL;
