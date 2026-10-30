CREATE TABLE command_result (
	actor_id UUID NOT NULL REFERENCES app_user (id),
	idempotency_key VARCHAR(128) NOT NULL,
	request_hash CHAR(64) NOT NULL,
	response_body TEXT,
	created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
	PRIMARY KEY (actor_id, idempotency_key)
);

CREATE FUNCTION check_completed_command() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
	IF EXISTS (SELECT 1 FROM command_result WHERE actor_id = NEW.actor_id
		AND idempotency_key = NEW.idempotency_key AND response_body IS NULL) THEN
		RAISE EXCEPTION 'An idempotent command must commit with its result' USING ERRCODE = '23514';
	END IF;
	RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER check_completed_command AFTER INSERT OR UPDATE ON command_result
	DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_completed_command();

CREATE FUNCTION guard_command_result() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
	IF TG_OP = 'DELETE' OR OLD.response_body IS NOT NULL
		OR (to_jsonb(NEW) - 'response_body') <> (to_jsonb(OLD) - 'response_body') THEN
		RAISE EXCEPTION 'Completed command results are immutable' USING ERRCODE = '23514';
	END IF;
	RETURN NEW;
END $$;
CREATE TRIGGER guard_command_result BEFORE UPDATE OR DELETE ON command_result
	FOR EACH ROW EXECUTE FUNCTION guard_command_result();

CREATE TABLE audit_event (
	id UUID PRIMARY KEY,
	actor_id UUID NOT NULL REFERENCES app_user (id),
	action VARCHAR(32) NOT NULL,
	operation_id UUID NOT NULL UNIQUE REFERENCES payment_operation (id),
	request_id VARCHAR(64) NOT NULL,
	created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX audit_event_history ON audit_event (created_at DESC, id);

CREATE FUNCTION guard_audit_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
	RAISE EXCEPTION 'Audit events are immutable' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER guard_audit_event BEFORE UPDATE OR DELETE ON audit_event
	FOR EACH ROW EXECUTE FUNCTION guard_audit_event();
