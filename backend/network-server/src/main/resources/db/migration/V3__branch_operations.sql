ALTER TABLE stock_requests ADD COLUMN branch_id TEXT;
ALTER TABLE stock_requests ADD CONSTRAINT stock_requests_branch_id_check
    CHECK (branch_id IS NULL OR branch_id ~ '^[A-Za-z0-9_-]{1,40}$');
ALTER TABLE stock_requests DROP CONSTRAINT stock_requests_status_check;
ALTER TABLE stock_requests ADD CONSTRAINT stock_requests_status_check
    CHECK (status IN ('ACCEPTED', 'REJECTED', 'PICKED', 'DISPATCHED')
        OR (status = 'CANCELLED' AND branch_id IS NOT NULL));

CREATE INDEX idx_workspace_requests_cursor
    ON stock_requests (created_at DESC, request_id DESC) WHERE branch_id IS NOT NULL;
CREATE INDEX idx_workspace_branch_requests_cursor
    ON stock_requests (branch_id, created_at DESC, request_id DESC);

ALTER TABLE stock_movements ALTER COLUMN stock_request_id DROP NOT NULL;
ALTER TABLE stock_movements ADD COLUMN receiving_command_id UUID UNIQUE
    REFERENCES command_receipts (command_request_id);
ALTER TABLE stock_movements ADD COLUMN recorded_by TEXT;
ALTER TABLE stock_movements ADD COLUMN note TEXT;
ALTER TABLE stock_movements DROP CONSTRAINT stock_movements_quantity_delta_check;
ALTER TABLE stock_movements DROP CONSTRAINT stock_movements_reason_check;
ALTER TABLE stock_movements ADD CONSTRAINT stock_movements_kind_check CHECK (
    (reason = 'DISPATCH' AND quantity_delta < 0 AND stock_request_id IS NOT NULL
        AND receiving_command_id IS NULL AND recorded_by IS NULL AND note IS NULL)
    OR
    (reason = 'RECEIVE' AND quantity_delta > 0 AND stock_request_id IS NULL
        AND receiving_command_id IS NOT NULL AND recorded_by IS NOT NULL
        AND note IS NOT NULL AND length(note) BETWEEN 1 AND 160)
);
