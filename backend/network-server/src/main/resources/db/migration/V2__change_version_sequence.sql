-- Monotonic change version sequence for Java NIO cache invalidation signals (#67).
-- Durable sequence guarantees strictly increasing version numbers across warehouse server restarts.
CREATE SEQUENCE IF NOT EXISTS warehouse_change_version_seq
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    NO CYCLE;
