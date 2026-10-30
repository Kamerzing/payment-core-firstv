-- Stage 2 has no monetary commands: refuse an unexplained opening balance.
DO $$ BEGIN
	IF EXISTS (SELECT 1 FROM ledger_account WHERE balance_minor <> 0) THEN
		RAISE EXCEPTION 'Opening balances must be reconciled before installing the ledger';
	END IF;
END $$;

CREATE TABLE payment_operation (
	id UUID PRIMARY KEY,
	actor_id UUID NOT NULL REFERENCES app_user (id),
	type VARCHAR(32) NOT NULL CHECK (type IN ('DEPOSIT', 'TRANSFER', 'WITHDRAWAL', 'REVERSAL')),
	state VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT', 'POSTED')),
	source_wallet_id UUID REFERENCES wallet (id),
	target_wallet_id UUID REFERENCES wallet (id),
	amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
	fee_minor BIGINT NOT NULL CHECK (fee_minor >= 0),
	fee_basis_points INTEGER NOT NULL CHECK (fee_basis_points BETWEEN 0 AND 10000),
	original_operation_id UUID UNIQUE REFERENCES payment_operation (id),
	created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
	posted_at TIMESTAMPTZ,
	CHECK (amount_minor::numeric + fee_minor <= 9223372036854775807),
	CHECK ((type = 'REVERSAL') = (original_operation_id IS NOT NULL)),
	CHECK (source_wallet_id IS DISTINCT FROM target_wallet_id)
);

CREATE INDEX payment_operation_source_history ON payment_operation (source_wallet_id, created_at DESC, id);
CREATE INDEX payment_operation_target_history ON payment_operation (target_wallet_id, created_at DESC, id);

CREATE TABLE ledger_posting (
	id UUID PRIMARY KEY,
	operation_id UUID NOT NULL REFERENCES payment_operation (id),
	account_id UUID NOT NULL REFERENCES ledger_account (id),
	amount_minor BIGINT NOT NULL CHECK (amount_minor <> 0),
	UNIQUE (operation_id, account_id)
);
CREATE INDEX ledger_posting_account ON ledger_posting (account_id);

CREATE FUNCTION guard_payment_operation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
	IF TG_OP = 'INSERT' THEN
		IF NEW.state <> 'DRAFT' OR NEW.posted_at IS NOT NULL THEN
			RAISE EXCEPTION 'An operation must start as a draft' USING ERRCODE = '23514';
		END IF;
		RETURN NEW;
	END IF;
	IF TG_OP = 'DELETE' OR OLD.state = 'POSTED' THEN
		RAISE EXCEPTION 'The operation journal is immutable' USING ERRCODE = '23514';
	END IF;
	IF NEW.state <> 'POSTED' OR NEW.posted_at IS NULL
		OR (to_jsonb(NEW) - 'state' - 'posted_at') <> (to_jsonb(OLD) - 'state' - 'posted_at') THEN
		RAISE EXCEPTION 'Only posting a draft is allowed' USING ERRCODE = '23514';
	END IF;
	RETURN NEW;
END $$;
CREATE TRIGGER guard_payment_operation BEFORE INSERT OR UPDATE OR DELETE ON payment_operation
	FOR EACH ROW EXECUTE FUNCTION guard_payment_operation();

CREATE FUNCTION apply_ledger_posting() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_state VARCHAR(16);
BEGIN
	IF TG_OP <> 'INSERT' THEN
		RAISE EXCEPTION 'Postings are immutable' USING ERRCODE = '23514';
	END IF;
	-- Serializes insertion against posting the operation, including other connections.
	SELECT state INTO operation_state FROM payment_operation WHERE id = NEW.operation_id FOR UPDATE;
	IF operation_state IS DISTINCT FROM 'DRAFT' THEN
		RAISE EXCEPTION 'Cannot append to a posted operation' USING ERRCODE = '23514';
	END IF;
	-- PostgreSQL also checks BIGINT overflow and the nonnegative account constraint.
	UPDATE ledger_account SET balance_minor = balance_minor + NEW.amount_minor WHERE id = NEW.account_id;
	RETURN NEW;
END $$;
CREATE TRIGGER apply_ledger_posting BEFORE INSERT OR UPDATE OR DELETE ON ledger_posting
	FOR EACH ROW EXECUTE FUNCTION apply_ledger_posting();

CREATE FUNCTION check_posted_operation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_state VARCHAR(16); posting_count BIGINT; posting_sum NUMERIC;
BEGIN
	SELECT state INTO operation_state FROM payment_operation WHERE id = NEW.id;
	SELECT count(*), COALESCE(sum(amount_minor), 0) INTO posting_count, posting_sum
		FROM ledger_posting WHERE operation_id = NEW.id;
	IF operation_state <> 'POSTED' OR posting_count < 2 OR posting_sum <> 0 THEN
		RAISE EXCEPTION 'An operation must commit posted and balanced' USING ERRCODE = '23514';
	END IF;
	RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER check_posted_operation AFTER INSERT OR UPDATE ON payment_operation
	DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_posted_operation();

CREATE FUNCTION guard_account_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
	IF TG_OP = 'INSERT' AND NEW.balance_minor <> 0 THEN
		RAISE EXCEPTION 'Accounts must open at zero' USING ERRCODE = '23514';
	END IF;
	IF TG_OP = 'UPDATE' AND (NEW.id, NEW.wallet_id, NEW.kind) IS DISTINCT FROM (OLD.id, OLD.wallet_id, OLD.kind) THEN
		RAISE EXCEPTION 'Account identity is immutable' USING ERRCODE = '23514';
	END IF;
	RETURN NEW;
END $$;
CREATE TRIGGER guard_account_identity BEFORE INSERT OR UPDATE ON ledger_account
	FOR EACH ROW EXECUTE FUNCTION guard_account_identity();

CREATE FUNCTION check_account_balance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE cached BIGINT; journal NUMERIC;
BEGIN
	SELECT balance_minor INTO cached FROM ledger_account WHERE id = NEW.id;
	SELECT COALESCE(sum(amount_minor), 0) INTO journal FROM ledger_posting WHERE account_id = NEW.id;
	IF cached::numeric IS DISTINCT FROM journal THEN
		RAISE EXCEPTION 'Account balance must equal its journal' USING ERRCODE = '23514';
	END IF;
	RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER check_account_balance AFTER INSERT OR UPDATE ON ledger_account
	DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_account_balance();
