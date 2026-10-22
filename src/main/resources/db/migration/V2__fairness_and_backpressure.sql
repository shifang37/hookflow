-- =====================================================================
-- HookFlow v0.3 schema: make the backlog *schedulable*.
--
-- The v0.2 relay published outbox rows strictly in id order, so one tenant's
-- 10 000 rows were 10 000 messages in front of everybody else's work: a single
-- broker queue plus FIFO consumption starves every small tenant behind the big
-- one. v0.3 decides *which* row may become a broker message, which means the
-- outbox has to carry the two dimensions the decision is made on.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Per-tenant fairness knobs.
--
--   max_in_flight  concurrency budget: how many of this tenant's deliveries may
--                  be DELIVERING at once. This is the isolation fence — it also
--                  bounds how far ahead of any other tenant this tenant's work
--                  can be queued (see docs/adr/0009-fair-scheduling.md).
--   weight         relative share when tenants contend (weighted round robin).
--                  weight 2 means "twice as many admission turns as weight 1".
-- ---------------------------------------------------------------------
ALTER TABLE tenant
    ADD COLUMN max_in_flight INT NOT NULL DEFAULT 32,
    ADD COLUMN weight       INT NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_tenant_max_in_flight CHECK (max_in_flight >= 0),
    ADD CONSTRAINT ck_tenant_weight CHECK (weight BETWEEN 1 AND 100);

-- ---------------------------------------------------------------------
-- 2. Per-endpoint concurrency cap.
--
-- 0 means "no endpoint-level cap; the tenant budget is the only fence". A
-- receiver that cannot take more than N parallel POSTs sets this higher than
-- zero and stops being the reason a whole tenant stalls.
-- ---------------------------------------------------------------------
ALTER TABLE endpoint
    ADD COLUMN max_concurrency INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_endpoint_max_concurrency CHECK (max_concurrency >= 0);

-- ---------------------------------------------------------------------
-- 3. Outbox becomes a *schedulable* queue.
--
-- tenant_id / endpoint_id are denormalised on purpose: they are known at append
-- time, and without them every relay tick would have to join delivery to decide
-- who is at their budget. Both are NOT NULL with a default so this migration is
-- an INSTANT ALTER on a large table.
-- ---------------------------------------------------------------------
ALTER TABLE outbox
    ADD COLUMN tenant_id   BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN endpoint_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    -- scheduler scan: WHERE status='NEW' AND tenant_id NOT IN (...)
    ADD KEY idx_outbox_status_tenant (status, tenant_id, id),
    ADD KEY idx_outbox_status_endpoint (status, endpoint_id, id),
    -- ingest shedding: COUNT(*) per tenant over the un-published backlog
    ADD KEY idx_outbox_tenant_status (tenant_id, status);

-- Backfill rows written by v0.2. The delivery row is the authority for both ids.
UPDATE outbox o
    JOIN delivery d ON d.id = o.aggregate_id AND o.aggregate_type = 'DELIVERY'
   SET o.tenant_id = d.tenant_id,
       o.endpoint_id = d.endpoint_id
 WHERE o.tenant_id = 0;

-- ---------------------------------------------------------------------
-- 4. In-flight counting.
--
-- The relay asks "how many of tenant X / endpoint Y are DELIVERING right now".
-- (endpoint_id, status) already exists (idx_delivery_endpoint_status); the
-- tenant dimension needs its own covering index.
-- ---------------------------------------------------------------------
ALTER TABLE delivery
    ADD KEY idx_delivery_tenant_status (tenant_id, status);
