#!/bin/sh
set -eu
umask 077
directory=${1:?Usage: sh init-secrets.sh /absolute/path/outside/checkout}
case "$directory" in /*) ;; *) echo 'Use an absolute secret directory.' >&2; exit 2 ;; esac
mkdir -p "$directory"
chmod 700 "$directory"
for name in postgres_admin_password migrator_password app_password backup_password restic_password; do
  if [ ! -e "$directory/$name" ]; then
    openssl rand -hex 32 > "$directory/$name"
    # Private parent on the host; individual bind mounts are read by non-root containers.
    chmod 444 "$directory/$name"
  fi
done
if [ ! -e "$directory/restic_config" ]; then
  printf '%s\n' '# Configure a remote repository before backup init.' \
    'RESTIC_REPOSITORY=s3:https://s3.example.com/payment-core-backups' \
    'AWS_ACCESS_KEY_ID=replace-me' 'AWS_SECRET_ACCESS_KEY=replace-me' > "$directory/restic_config"
  chmod 444 "$directory/restic_config"
fi
echo 'Secret files prepared; edit restic_config securely before initializing backups.'
