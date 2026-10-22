-- =====================================================================
-- HookFlow v0.1 initial schema
--
-- Design notes (see docs/adr/ for the reasoning):
--   * MySQL is the single source of truth. Redis never holds event state.
--   * delivery.status transitions are guarded by compare-and-set updates;
--     the enum values here are the only legal set.
--   * outbox is keyed by (aggregate_type, aggregate_id, attempt_no) so that
--     retry round N+1 can be enqueued without colliding with round N.
-- =====================================================================

CREATE TABLE tenant (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64)     NOT NULL,
    -- events per second allowed for this tenant; 0 disables rate limiting
    rate_limit  INT             NOT NULL DEFAULT 100,
    status      VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_name (name),
    CONSTRAINT ck_tenant_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE api_key (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT UNSIGNED NOT NULL,
    -- public, non-secret part used to look the key up without a table scan
    key_prefix  CHAR(8)         NOT NULL,
    -- SHA-256(secret). The plaintext secret is never persisted.
    key_hash    CHAR(64)        NOT NULL,
    scopes      VARCHAR(255)    NOT NULL DEFAULT 'event:write,endpoint:write,delivery:read',
    status      VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    revoked_at  DATETIME(3)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_api_key_prefix (key_prefix),
    KEY idx_api_key_tenant (tenant_id),
    CONSTRAINT fk_api_key_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT ck_api_key_status CHECK (status IN ('ACTIVE', 'REVOKED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE endpoint (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT UNSIGNED NOT NULL,
    url                VARCHAR(2048)   NOT NULL,
    -- AES-256-GCM ciphertext of the per-endpoint signing secret.
    secret_cipher      VARBINARY(512)  NOT NULL,
    secret_key_version SMALLINT        NOT NULL DEFAULT 1,
    -- comma separated event types, e.g. 'order.paid,order.refunded'. '*' matches all.
    subscribed_types   VARCHAR(1000)   NOT NULL DEFAULT '*',
    max_attempts       INT             NOT NULL DEFAULT 8,
    status             VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_endpoint_tenant_url (tenant_id, url(191)),
    KEY idx_endpoint_tenant_status (tenant_id, status),
    CONSTRAINT fk_endpoint_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT ck_endpoint_status CHECK (status IN ('ACTIVE', 'PAUSED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE event (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT UNSIGNED NOT NULL,
    event_type      VARCHAR(128)    NOT NULL,
    payload         JSON            NOT NULL,
    idempotency_key VARCHAR(255)    NOT NULL,
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- the single source of idempotency: duplicate submissions surface as 1062
    UNIQUE KEY uk_event_tenant_idem (tenant_id, idempotency_key),
    KEY idx_event_tenant_type_created (tenant_id, event_type, created_at),
    CONSTRAINT fk_event_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE delivery (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id        BIGINT UNSIGNED NOT NULL,
    event_id         BIGINT UNSIGNED NOT NULL,
    endpoint_id      BIGINT UNSIGNED NOT NULL,
    status           VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    -- number of attempts already claimed; the next attempt is attempt_count + 1
    attempt_count    INT             NOT NULL DEFAULT 0,
    max_attempts     INT             NOT NULL DEFAULT 8,
    next_attempt_at  DATETIME(3)     NULL,
    -- fencing token: every write that leaves DELIVERING must match it
    lease_token      CHAR(36)        NULL,
    lease_until      DATETIME(3)     NULL,
    last_error_class VARCHAR(32)     NULL,
    last_status_code SMALLINT        NULL,
    created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- one delivery per (event, endpoint): re-fan-out cannot duplicate
    UNIQUE KEY uk_delivery_event_endpoint (event_id, endpoint_id),
    -- retry scan: WHERE status='RETRY_WAIT' AND next_attempt_at <= now()
    KEY idx_delivery_due (status, next_attempt_at),
    -- lease reclaim: WHERE status='DELIVERING' AND lease_until < now()
    KEY idx_delivery_lease (status, lease_until),
    KEY idx_delivery_endpoint_status (endpoint_id, status),
    CONSTRAINT fk_delivery_event FOREIGN KEY (event_id) REFERENCES event (id),
    CONSTRAINT fk_delivery_endpoint FOREIGN KEY (endpoint_id) REFERENCES endpoint (id),
    CONSTRAINT ck_delivery_status CHECK (
        status IN ('PENDING', 'DELIVERING', 'RETRY_WAIT', 'SUCCEEDED', 'DEAD', 'CANCELED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE delivery_attempt (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    delivery_id          BIGINT UNSIGNED NOT NULL,
    attempt_no           INT             NOT NULL,
    status               VARCHAR(16)     NOT NULL DEFAULT 'IN_PROGRESS',
    status_code          SMALLINT        NULL,
    -- truncated; large bodies and sensitive headers are never persisted
    response_preview     VARCHAR(1024)   NULL,
    error_class          VARCHAR(32)     NULL,
    duration_ms          INT             NULL,
    started_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at          DATETIME(3)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attempt_delivery_no (delivery_id, attempt_no),
    KEY idx_attempt_delivery (delivery_id, attempt_no),
    CONSTRAINT fk_attempt_delivery FOREIGN KEY (delivery_id) REFERENCES delivery (id),
    CONSTRAINT ck_attempt_status CHECK (status IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'ABANDONED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE outbox (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    aggregate_type   VARCHAR(32)     NOT NULL,
    aggregate_id     BIGINT UNSIGNED NOT NULL,
    -- the attempt number this message will execute; part of the uniqueness key
    attempt_no       INT             NOT NULL,
    payload          JSON            NOT NULL,
    status           VARCHAR(16)     NOT NULL DEFAULT 'NEW',
    publishing_since DATETIME(3)     NULL,
    publish_attempts INT             NOT NULL DEFAULT 0,
    created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at     DATETIME(3)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_aggregate_attempt (aggregate_type, aggregate_id, attempt_no),
    KEY idx_outbox_status_id (status, id),
    -- stale PUBLISHING sweeper: WHERE status='PUBLISHING' AND publishing_since < ...
    KEY idx_outbox_status_since (status, publishing_since),
    CONSTRAINT ck_outbox_status CHECK (status IN ('NEW', 'PUBLISHING', 'PUBLISHED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE audit_log (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT UNSIGNED NOT NULL,
    actor       VARCHAR(64)     NOT NULL,
    action      VARCHAR(64)     NOT NULL,
    target_type VARCHAR(32)     NOT NULL,
    target_id   BIGINT UNSIGNED NOT NULL,
    detail      JSON            NULL,
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_audit_tenant_created (tenant_id, created_at),
    KEY idx_audit_target (target_type, target_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
