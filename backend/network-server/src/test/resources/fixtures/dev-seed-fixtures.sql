-- Synthetic inventory seed fixtures for development and testing (Issue #8 & QA-06)
-- Strictly for testing/demos; do NOT execute in production environments.

-- 1. Product stock catalog with stocked, low-stock, and zero-stock SKUs
INSERT INTO product_stock (sku, name, on_hand, reserved)
VALUES
    ('ITEM-007', 'Organic Carrots', 10, 2),
    ('ITEM-008', 'Red Onions', 0, 0),
    ('ITEM-009', 'Yukon Gold Potatoes', 5, 3),
    ('ITEM-010', 'Fresh Leeks', 4, 0),
    ('ITEM-011', 'Ceylon Garlic', 1, 0),
    ('ITEM-012', 'Fresh Ginger', 3, 0)
ON CONFLICT (sku) DO UPDATE
SET name = EXCLUDED.name,
    on_hand = EXCLUDED.on_hand,
    reserved = EXCLUDED.reserved;

-- 2. Representative requests across all lifecycle states
INSERT INTO stock_requests (request_id, request_fingerprint, sku, quantity, status, decision_reason)
VALUES
    ('a0000001-0000-4000-8000-000000000001', '0000000000000000000000000000000000000000000000000000000000000000', 'ITEM-007', 2, 'ACCEPTED', NULL),
    ('a0000002-0000-4000-8000-000000000002', '0000000000000000000000000000000000000000000000000000000000000000', 'ITEM-009', 3, 'PICKED', NULL),
    ('a0000003-0000-4000-8000-000000000003', '0000000000000000000000000000000000000000000000000000000000000000', 'ITEM-007', 1, 'DISPATCHED', NULL),
    ('a0000004-0000-4000-8000-000000000004', '0000000000000000000000000000000000000000000000000000000000000000', 'ITEM-008', 5, 'REJECTED', 'Requested quantity 5 exceeds available stock 0')
ON CONFLICT (request_id) DO UPDATE
SET status = EXCLUDED.status,
    decision_reason = EXCLUDED.decision_reason;

-- 3. Audit trail movement for completed dispatch
INSERT INTO stock_movements (stock_request_id, sku, quantity_delta, reason)
VALUES
    ('a0000003-0000-4000-8000-000000000003', 'ITEM-007', -1, 'DISPATCH')
ON CONFLICT (stock_request_id) DO NOTHING;
