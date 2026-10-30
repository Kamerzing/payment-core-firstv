CREATE TABLE app_user (
	id UUID PRIMARY KEY,
	username VARCHAR(64) NOT NULL UNIQUE,
	password_hash VARCHAR(255) NOT NULL,
	role VARCHAR(16) NOT NULL CHECK (role IN ('USER', 'ADMIN')),
	enabled BOOLEAN NOT NULL DEFAULT TRUE,
	created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
	CHECK (username ~ '^[a-z0-9][a-z0-9._-]{2,63}$'),
	CHECK (password_hash LIKE '$argon2id$%')
);

CREATE TABLE wallet (
	id UUID PRIMARY KEY,
	owner_id UUID NOT NULL UNIQUE REFERENCES app_user (id),
	currency VARCHAR(3) NOT NULL DEFAULT 'RUB' CHECK (currency = 'RUB'),
	created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE ledger_account (
	id UUID PRIMARY KEY,
	wallet_id UUID REFERENCES wallet (id),
	kind VARCHAR(16) NOT NULL CHECK (kind IN ('AVAILABLE', 'RESERVED', 'SETTLEMENT', 'FEES')),
	balance_minor BIGINT NOT NULL DEFAULT 0,
	UNIQUE (wallet_id, kind),
	CHECK ((wallet_id IS NOT NULL AND kind IN ('AVAILABLE', 'RESERVED'))
		OR (wallet_id IS NULL AND kind IN ('SETTLEMENT', 'FEES'))),
	CHECK (kind = 'SETTLEMENT' OR balance_minor >= 0)
);

CREATE UNIQUE INDEX ledger_account_system_kind ON ledger_account (kind) WHERE wallet_id IS NULL;

INSERT INTO ledger_account (id, kind) VALUES
	('00000000-0000-0000-0000-000000000001', 'SETTLEMENT'),
	('00000000-0000-0000-0000-000000000002', 'FEES');

-- Fixed windows survive application restarts and are shared by all application instances.
CREATE TABLE login_attempt_bucket (
	bucket_key CHAR(64) PRIMARY KEY,
	attempts INTEGER NOT NULL CHECK (attempts > 0),
	expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX login_attempt_bucket_expiry ON login_attempt_bucket (expires_at);
