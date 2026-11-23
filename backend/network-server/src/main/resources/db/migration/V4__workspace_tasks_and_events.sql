CREATE TABLE supplier_followups (
    id uuid PRIMARY KEY,
    sku text NOT NULL REFERENCES product_stock,
    owner text NOT NULL,
    due_date date NOT NULL,
    created_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    closed_by text,
    closed_at timestamptz,
    CHECK ((closed_by IS NULL) = (closed_at IS NULL))
);
CREATE UNIQUE INDEX one_open_followup ON supplier_followups(sku) WHERE closed_at IS NULL;

CREATE TABLE request_events (
    sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES stock_requests ON DELETE CASCADE,
    stage text NOT NULL,
    happened_at timestamptz NOT NULL,
    UNIQUE (request_id, stage)
);
INSERT INTO request_events(request_id, stage, happened_at)
SELECT request_id, 'REQUESTED', created_at FROM stock_requests WHERE branch_id IS NOT NULL;
INSERT INTO request_events(request_id, stage, happened_at)
SELECT request_id, status, updated_at FROM stock_requests WHERE branch_id IS NOT NULL;

CREATE FUNCTION record_workspace_stage() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.branch_id IS NOT NULL THEN
        IF TG_OP = 'INSERT' THEN
            INSERT INTO request_events(request_id, stage, happened_at)
            VALUES (NEW.request_id, 'REQUESTED', NEW.created_at);
        END IF;
        IF TG_OP = 'INSERT' OR NEW.status IS DISTINCT FROM OLD.status THEN
            INSERT INTO request_events(request_id, stage, happened_at)
            VALUES (NEW.request_id, NEW.status, NEW.updated_at);
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER workspace_request_stage AFTER INSERT OR UPDATE OF status ON stock_requests
FOR EACH ROW EXECUTE FUNCTION record_workspace_stage();
