CREATE TABLE deliveries (
    id               VARCHAR(80) PRIMARY KEY,
    notification_id  VARCHAR(64)  NOT NULL,
    tenant_id        VARCHAR(64)  NOT NULL,
    channel          VARCHAR(16)  NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    attempts         INT          NOT NULL DEFAULT 0,
    last_error       VARCHAR(1024),
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_deliveries_tenant_created ON deliveries (tenant_id, created_at DESC);
CREATE INDEX idx_deliveries_notification ON deliveries (notification_id);
