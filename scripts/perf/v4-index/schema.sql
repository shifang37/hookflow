-- =====================================================================
-- V4 index benchmark: schema.
--
-- The four tables the settlement chart and the settle path touch, in the
-- shape V1 gives them (V2/V3 only add endpoint.max_concurrency and an
-- index on delivery_attempt, neither of which this benchmark touches).
--
-- Deliberately NOT here: idx_delivery_updated_status. The whole point is
-- to add and drop it, so the arm without it is a real "before".
--
-- delivery_write is a FK-less copy used for the write benchmarks, so the
-- read dataset stays untouched between the two arms.
-- =====================================================================

CREATE TABLE tenant (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64)     NOT NULL,
    rate_limit  INT             NOT NULL DEFAULT 100,
    status      VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE endpoint (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT UNSIGNED NOT NULL,
    url                VARCHAR(2048)   NOT NULL,
    secret_cipher      VARBINARY(512)  NOT NULL,
    secret_key_version SMALLINT        NOT NULL DEFAULT 1,
    subscribed_types   VARCHAR(1000)   NOT NULL DEFAULT '*',
    max_attempts       INT             NOT NULL DEFAULT 8,
    status             VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_endpoint_tenant_url (tenant_id, url(191)),
    KEY idx_endpoint_tenant_status (tenant_id, status),
    CONSTRAINT fk_endpoint_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE event (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT UNSIGNED NOT NULL,
    event_type      VARCHAR(128)    NOT NULL,
    payload         JSON            NOT NULL,
    idempotency_key VARCHAR(255)    NOT NULL,
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
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
    attempt_count    INT             NOT NULL DEFAULT 0,
    max_attempts     INT             NOT NULL DEFAULT 8,
    next_attempt_at  DATETIME(3)     NULL,
    lease_token      CHAR(36)        NULL,
    lease_until      DATETIME(3)     NULL,
    last_error_class VARCHAR(32)     NULL,
    last_status_code SMALLINT        NULL,
    created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_delivery_event_endpoint (event_id, endpoint_id),
    KEY idx_delivery_due (status, next_attempt_at),
    KEY idx_delivery_lease (status, lease_until),
    KEY idx_delivery_endpoint_status (endpoint_id, status),
    CONSTRAINT fk_delivery_event FOREIGN KEY (event_id) REFERENCES event (id),
    CONSTRAINT fk_delivery_endpoint FOREIGN KEY (endpoint_id) REFERENCES endpoint (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Same columns and secondary keys as `delivery`, and — because LIKE does not copy
-- foreign keys — no foreign keys. The write benchmark only needs index maintenance,
-- and parent rows would make the seed the measured thing instead of the update.
CREATE TABLE delivery_write LIKE delivery;
