CREATE TABLE webhooks (
    tenant_id   VARCHAR(64)   PRIMARY KEY,
    url         VARCHAR(2048) NOT NULL,
    secret      VARCHAR(128)  NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL
);
