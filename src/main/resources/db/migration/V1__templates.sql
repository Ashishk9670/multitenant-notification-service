CREATE TABLE templates (
    id          UUID PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    channel     VARCHAR(16)  NOT NULL,
    subject     VARCHAR(512),
    body        TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_template UNIQUE (tenant_id, name, channel)
);
CREATE INDEX idx_templates_tenant ON templates (tenant_id);
