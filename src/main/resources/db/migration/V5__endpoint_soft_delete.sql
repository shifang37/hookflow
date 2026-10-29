-- =====================================================================
-- HookFlow v0.5 schema: DELETE /v1/endpoints, as a soft delete.
--
-- Two schema facts stand in the way of "mark the row deleted":
--
--   1. ck_endpoint_status only allows ACTIVE and PAUSED (V1).
--   2. uk_endpoint_tenant_url (tenant_id, url(191)) is unconditional.
--
-- The first is a one-line change. The second is the interesting one: after
-- soft-deleting https://customer.example/hook, re-creating it - which is the
-- normal reason to delete one, the operator mistyped the attempt budget or lost
-- the signing secret - would hit the unique key against a row nobody can see
-- any more. Leaving the key alone would make 409 ALREADY_EXISTS permanent for a
-- URL an operator has already deleted, and a hard DELETE is not available
-- because delivery.endpoint_id is a foreign key: history must survive.
--
-- MySQL has no partial indexes, so url_key is the portable substitute: a stored
-- generated column that is the URL prefix while the endpoint is live and NULL
-- once it is DELETED. Unique indexes in InnoDB allow any number of NULLs, so
-- the constraint keeps doing its job (two live endpoints cannot share a URL)
-- and stops applying to rows that no longer exist as far as the API is
-- concerned.
--
-- LEFT(url, 191) keeps the V1 index width (191 * 4 bytes = 764 of the 3072-byte
-- limit). It is not a truncation a user can notice: the original key compared
-- the same 191 character prefix.
--
-- Why a status rather than a deleted_at column: the fan-out path already reads
-- `WHERE status = 'ACTIVE'` (listActiveByTenant), so DELETED is excluded from
-- fan-out with no query change at all, and the CHECK constraint keeps every
-- status-writing path honest. A deleted_at column would have needed the same
-- filter added to every reader of `endpoint` plus a rule that it must stay in
-- sync with `status`.
--
-- The cost, stated plainly: url_key is a second copy of the first 191
-- characters of every URL, maintained on insert. Writes to `endpoint` are rare
-- (an operator action, not a delivery), so this is not the delivery hot path
-- that V4's comment warns about.
-- =====================================================================

ALTER TABLE endpoint
    ADD COLUMN url_key VARCHAR(191)
        GENERATED ALWAYS AS (
            CASE WHEN status = 'DELETED' THEN NULL ELSE LEFT(url, 191) END
        ) STORED;

ALTER TABLE endpoint
    DROP INDEX uk_endpoint_tenant_url;

ALTER TABLE endpoint
    ADD UNIQUE KEY uk_endpoint_tenant_url (tenant_id, url_key);

ALTER TABLE endpoint
    DROP CHECK ck_endpoint_status;

ALTER TABLE endpoint
    ADD CONSTRAINT ck_endpoint_status
        CHECK (status IN ('ACTIVE', 'PAUSED', 'DELETED'));
