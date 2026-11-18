-- Synthetic inventory seed fixtures for development and testing (Issue #8)
-- Strictly for testing/demos; do NOT execute in production environments.

INSERT INTO product_stock (sku, name, on_hand, reserved)
VALUES
    ('ITEM-007', 'Whole Ceylon Black Pepper', 10, 0),
    ('ITEM-008', 'Ceylon Cinnamon Quills', 0, 0)
ON CONFLICT (sku) DO UPDATE
SET name = EXCLUDED.name,
    on_hand = EXCLUDED.on_hand,
    reserved = EXCLUDED.reserved;
