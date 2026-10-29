-- V4 index benchmark: the arm before the index existed.
ALTER TABLE delivery
    DROP INDEX idx_delivery_updated_status;
ALTER TABLE delivery_write
    DROP INDEX idx_delivery_updated_status;
