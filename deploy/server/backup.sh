#!/bin/sh
set -eu
umask 077
# Operator-controlled shell configuration, mounted as a secret.
set -a
. /run/secrets/restic_config
set +a
: "${RESTIC_REPOSITORY:?Configure an off-host restic repository}"
export RESTIC_PASSWORD_FILE=/run/secrets/restic_password
export RESTIC_CACHE_DIR=/tmp/restic-cache
case "${1:-backup}" in
  init) exec restic init ;;
  snapshots) shift; exec restic snapshots --host payment-core --tag postgres "$@" ;;
  check) exec restic check --read-data ;;
  backup)
    export PGUSER=payment_backup
    PGPASSWORD=$(cat /run/secrets/backup_password)
    export PGPASSWORD
    # A failed pg_dump must never create a successful snapshot.
    restic backup --host payment-core --tag postgres --stdin-filename payment-core.dump \
      --stdin-from-command -- pg_dump --format=custom --no-owner --no-privileges
    restic check
    restic forget --host payment-core --tag postgres --group-by host,tags \
      --keep-daily 14 --keep-weekly 8 --keep-monthly 12 --prune
    echo 'Encrypted database backup and repository check completed.'
    ;;
  *) echo 'Usage: backup.sh init|backup|snapshots|check' >&2; exit 2 ;;
esac
