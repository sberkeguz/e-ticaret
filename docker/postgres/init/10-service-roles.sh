#!/bin/sh
# Yalnızca veritabanı ilk kez oluşturulurken (boş volume) çalışır.
# Var olan bir veritabanında aynısını uygulamak için README'deki komuta bakın.
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v backend_password="$BACKEND_DB_PASSWORD" \
  -v iyzico_password="$IYZICO_DB_PASSWORD" \
  -f /docker-entrypoint-initdb.d/service-roles.sql.txt
