-- Starter schema for one-SKU stock reservations.
-- Review with Member 4 before adding implementation; use future migrations for changes.
CREATE TABLE product_stock (
    sku TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    on_hand INTEGER NOT NULL CHECK (on_hand >= 0),
    reserved INTEGER NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    CONSTRAINT ck_reserved_not_over_on_hand CHECK (reserved <= on_hand)
);

CREATE TABLE stock_requests (
    request_id UUID PRIMARY KEY,
    request_fingerprint CHAR(64) NOT NULL,
    sku TEXT NOT NULL REFERENCES product_stock (sku),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    status TEXT NOT NULL CHECK (status IN ('ACCEPTED', 'REJECTED', 'PICKED', 'DISPATCHED')),
    decision_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_stock_requests_status_created
    ON stock_requests (status, created_at);

-- Generic receipt for mutating network commands. In the same transaction as
-- each stock/state change, persist a replayable outcome for the command UUID.
CREATE TABLE command_receipts (
    command_request_id UUID PRIMARY KEY,
    command_type TEXT NOT NULL,
    payload_fingerprint CHAR(64) NOT NULL,
    result_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE stock_movements (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stock_request_id UUID NOT NULL UNIQUE REFERENCES stock_requests (request_id),
    sku TEXT NOT NULL REFERENCES product_stock (sku),
    quantity_delta INTEGER NOT NULL CHECK (quantity_delta < 0),
    reason TEXT NOT NULL CHECK (reason = 'DISPATCH'),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
