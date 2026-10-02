CREATE TABLE api_keys (
    id          UUID PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    key_prefix  VARCHAR(16)  NOT NULL,
    key_hash    VARCHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ,
    CONSTRAINT uq_api_key_hash UNIQUE (key_hash)
);
CREATE INDEX idx_api_keys_tenant ON api_keys (tenant_id);
