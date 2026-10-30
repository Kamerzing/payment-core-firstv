#!/bin/sh
set -eu
umask 077
set -a
. /run/secrets/restic_config
set +a
# Check after importing config: never restore over a production host or database.
[ "${PGHOST:-}" = restore-postgres ] && [ "${PGDATABASE:-}" = payment_restore ] || {
  echo 'Restore is restricted to restore-postgres/payment_restore.' >&2; exit 2;
}
: "${RESTIC_REPOSITORY:?Configure the backup repository}"
export RESTIC_PASSWORD_FILE=/run/secrets/restic_password RESTIC_CACHE_DIR=/tmp/restic-cache
export PGUSER=payment_migrator
PGPASSWORD=$(cat /run/secrets/migrator_password)
export PGPASSWORD
tables=$(psql --no-psqlrc --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")
[ "$tables" = 0 ] || { echo 'Restore database is not empty; use a fresh restore volume.' >&2; exit 2; }
restic dump --host payment-core --tag postgres "${1:-latest}" /payment-core.dump > /tmp/payment-core.dump
pg_restore --dbname="$PGDATABASE" --exit-on-error --single-transaction --no-owner --no-privileges /tmp/payment-core.dump
rm -f /tmp/payment-core.dump
echo 'Restore completed. Run verify-restore before promoting this database.'
