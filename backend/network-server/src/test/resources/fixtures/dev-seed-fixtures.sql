-- Synthetic fixtures. The statement installs once into empty migrated tables.
DO $seed$
BEGIN
    LOCK TABLE product_stock, stock_requests, stock_movements, command_receipts
        IN SHARE ROW EXCLUSIVE MODE;

    IF EXISTS (SELECT FROM product_stock)
        OR EXISTS (SELECT FROM stock_requests)
        OR EXISTS (SELECT FROM stock_movements)
        OR EXISTS (SELECT FROM command_receipts) THEN
        RAISE NOTICE 'Demo seed skipped because inventory tables already contain data';
        RETURN;
    END IF;

    -- 1. Product stock catalog with stocked, low-stock, and zero-stock SKUs
    INSERT INTO product_stock (sku, name, on_hand, reserved)
    VALUES
        ('ITEM-007', 'Organic Carrots', 10, 2),
        ('ITEM-008', 'Red Onions', 0, 0),
        ('ITEM-009', 'Yukon Gold Potatoes', 5, 3),
        ('ITEM-010', 'Fresh Leeks', 4, 0),
        ('ITEM-011', 'Ceylon Garlic', 1, 0),
        ('ITEM-012', 'Fresh Ginger', 3, 0);

    -- 2. Representative requests across all lifecycle states
    INSERT INTO stock_requests (request_id, request_fingerprint, sku, quantity, status, decision_reason)
    VALUES
        ('a0000001-0000-4000-8000-000000000001', '0000000000000000000000000000000000000000000000000000000000000000', 'ITEM-007', 2, 'ACCEPTED', NULL),
        ('a0000002-0000-4000-8000-000000000002', '0000000000000000000000000000000000000000000000000000000000000000', 'ITEM-009', 3, 'PICKED', NULL),
        ('a0000003-0000-4000-8000-000000000003', '0000000000000000000000000000000000000000000000000000000000000000', 'ITEM-007', 1, 'DISPATCHED', NULL),
        ('a0000004-0000-4000-8000-000000000004', '0000000000000000000000000000000000000000000000000000000000000000', 'ITEM-008', 5, 'REJECTED', 'Requested quantity 5 exceeds available stock 0');

    -- 3. Audit trail movement for completed dispatch
    INSERT INTO stock_movements (stock_request_id, sku, quantity_delta, reason)
    VALUES
        ('a0000003-0000-4000-8000-000000000003', 'ITEM-007', -1, 'DISPATCH');
    RAISE NOTICE 'Synthetic demo inventory installed';
END;
$seed$;
