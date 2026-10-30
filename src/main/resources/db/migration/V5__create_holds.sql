ALTER TABLE payment_operation DROP CONSTRAINT payment_operation_type_check;
ALTER TABLE payment_operation ADD CONSTRAINT payment_operation_type_check CHECK (type IN (
	'DEPOSIT', 'TRANSFER', 'WITHDRAWAL', 'REVERSAL', 'HOLD_CREATE', 'HOLD_CANCEL', 'HOLD_EXPIRE',
	'CAPTURE_TRANSFER', 'CAPTURE_WITHDRAWAL'));

CREATE TABLE payment_hold (
	id UUID PRIMARY KEY,
	wallet_id UUID NOT NULL REFERENCES wallet (id),
	target_wallet_id UUID REFERENCES wallet (id),
	type VARCHAR(16) NOT NULL CHECK (type IN ('TRANSFER', 'WITHDRAWAL')),
	state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE', 'CAPTURED', 'CANCELLED', 'EXPIRED')),
	amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
	fee_minor BIGINT NOT NULL CHECK (fee_minor >= 0),
	fee_basis_points INTEGER NOT NULL CHECK (fee_basis_points BETWEEN 0 AND 10000),
	creation_operation_id UUID NOT NULL UNIQUE REFERENCES payment_operation (id),
	completion_operation_id UUID UNIQUE REFERENCES payment_operation (id),
	created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
	expires_at TIMESTAMPTZ NOT NULL DEFAULT (clock_timestamp() + interval '15 minutes'),
	completed_at TIMESTAMPTZ,
	CHECK ((type = 'TRANSFER') = (target_wallet_id IS NOT NULL)),
	CHECK (wallet_id IS DISTINCT FROM target_wallet_id),
	CHECK (amount_minor::numeric + fee_minor <= 9223372036854775807),
	CHECK (expires_at > created_at),
	CHECK ((state = 'ACTIVE' AND completion_operation_id IS NULL AND completed_at IS NULL)
		OR (state <> 'ACTIVE' AND completion_operation_id IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE INDEX payment_hold_expiration ON payment_hold (expires_at, id) WHERE state = 'ACTIVE';
CREATE INDEX payment_hold_history ON payment_hold (wallet_id, created_at DESC, id);

CREATE FUNCTION guard_payment_hold() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
	IF TG_OP = 'INSERT' THEN
		IF NEW.state <> 'ACTIVE' THEN RAISE EXCEPTION 'Holds must start active' USING ERRCODE = '23514'; END IF;
		RETURN NEW;
	END IF;
	IF TG_OP = 'DELETE' OR OLD.state <> 'ACTIVE' OR NEW.state = 'ACTIVE'
		OR (to_jsonb(NEW) - 'state' - 'completion_operation_id' - 'completed_at')
			<> (to_jsonb(OLD) - 'state' - 'completion_operation_id' - 'completed_at') THEN
		RAISE EXCEPTION 'Only one terminal hold transition is allowed' USING ERRCODE = '23514';
	END IF;
	IF NEW.state = 'CAPTURED' AND OLD.expires_at <= clock_timestamp() THEN
		RAISE EXCEPTION 'An expired hold cannot be captured' USING ERRCODE = 'P5001';
	END IF;
	IF NEW.state = 'EXPIRED' AND OLD.expires_at > clock_timestamp() THEN
		RAISE EXCEPTION 'A live hold cannot expire' USING ERRCODE = '23514';
	END IF;
	RETURN NEW;
END $$;
CREATE TRIGGER guard_payment_hold BEFORE INSERT OR UPDATE OR DELETE ON payment_hold
	FOR EACH ROW EXECUTE FUNCTION guard_payment_hold();

CREATE FUNCTION check_reserved_holds() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE wallet UUID; reserved BIGINT; active_total NUMERIC; wallet_total NUMERIC;
BEGIN
	IF TG_TABLE_NAME = 'ledger_account' THEN
		IF NEW.wallet_id IS NULL THEN RETURN NULL; END IF;
	END IF;
	wallet := NEW.wallet_id;
	SELECT sum(balance_minor::numeric) INTO wallet_total FROM ledger_account WHERE wallet_id = wallet;
	IF wallet_total > 9223372036854775807 THEN
		RAISE EXCEPTION 'Wallet capacity must allow releasing all active holds' USING ERRCODE = '23514';
	END IF;
	SELECT balance_minor INTO reserved FROM ledger_account WHERE wallet_id = wallet AND kind = 'RESERVED';
	SELECT COALESCE(sum(amount_minor::numeric + fee_minor), 0) INTO active_total
		FROM payment_hold WHERE wallet_id = wallet AND state = 'ACTIVE';
	IF reserved::numeric IS DISTINCT FROM active_total THEN
		RAISE EXCEPTION 'Reserved balance must equal active holds' USING ERRCODE = '23514';
	END IF;
	RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER check_reserved_holds AFTER INSERT OR UPDATE ON payment_hold
	DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_reserved_holds();
CREATE CONSTRAINT TRIGGER check_reserved_holds AFTER INSERT OR UPDATE ON ledger_account
	DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_reserved_holds();
