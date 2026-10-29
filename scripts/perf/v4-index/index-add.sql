-- V4 index benchmark: the arm with the index (the shipped state).
ALTER TABLE delivery
    ADD KEY idx_delivery_updated_status (updated_at, status);
ALTER TABLE delivery_write
    ADD KEY idx_delivery_updated_status (updated_at, status);
