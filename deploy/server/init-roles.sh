#!/bin/sh
# Run in a subshell: the PostgreSQL entrypoint may source non-executable scripts.
(
set -eu
# psql variables quote passwords as SQL literals, never as SQL source or CLI args.
PAYMENT_MIGRATOR_PASSWORD=$(cat /run/secrets/migrator_password)
PAYMENT_APP_PASSWORD=$(cat /run/secrets/app_password)
PAYMENT_BACKUP_PASSWORD=$(cat /run/secrets/backup_password)
export PAYMENT_MIGRATOR_PASSWORD PAYMENT_APP_PASSWORD PAYMENT_BACKUP_PASSWORD
psql --username postgres --dbname "$POSTGRES_DB" --no-psqlrc --set ON_ERROR_STOP=1 <<'SQL'
\getenv migrator_password PAYMENT_MIGRATOR_PASSWORD
\getenv app_password PAYMENT_APP_PASSWORD
\getenv backup_password PAYMENT_BACKUP_PASSWORD
CREATE ROLE payment_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'migrator_password';
CREATE ROLE payment_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'app_password';
CREATE ROLE payment_backup LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'backup_password';
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database()) \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO payment_migrator, payment_app, payment_backup', current_database()) \gexec
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO payment_migrator;
GRANT USAGE ON SCHEMA public TO payment_app, payment_backup;
ALTER DEFAULT PRIVILEGES FOR ROLE payment_migrator IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO payment_app;
ALTER DEFAULT PRIVILEGES FOR ROLE payment_migrator IN SCHEMA public GRANT SELECT ON TABLES TO payment_backup;
ALTER DEFAULT PRIVILEGES FOR ROLE payment_migrator IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE payment_migrator IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO payment_app;
SQL
)
