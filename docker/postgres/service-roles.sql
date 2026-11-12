-- Her servis kendi veritabanı kullanıcısıyla, yalnızca kendi şemasına erişir.
--   backend_app → public şeması (siparişler, sepet, kullanıcılar)
--   iyzico_app  → iyzico şeması (ödeme denemeleri, outbox)
-- POSTGRES_USER (eticaret_user) süper kullanıcıdır; yalnızca yönetim (pgAdmin) içindir.
--
-- Tekrar tekrar çalıştırılabilir. Kullanım:
--   psql -v backend_password=... -v iyzico_password=... -f service-roles.sql

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'backend_app') THEN
        CREATE ROLE backend_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'iyzico_app') THEN
        CREATE ROLE iyzico_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
    END IF;
END $$;

ALTER ROLE backend_app PASSWORD :'backend_password';
ALTER ROLE iyzico_app PASSWORD :'iyzico_password';
GRANT CONNECT ON DATABASE eticaret TO backend_app, iyzico_app;

-- ---------------------------------------------------------------- public → backend_app
ALTER SCHEMA public OWNER TO backend_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
DO $$
DECLARE r record;
BEGIN
    FOR r IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' LOOP
        EXECUTE format('ALTER TABLE public.%I OWNER TO backend_app', r.tablename);
    END LOOP;
    FOR r IN SELECT sequence_name FROM information_schema.sequences WHERE sequence_schema = 'public' LOOP
        EXECUTE format('ALTER SEQUENCE public.%I OWNER TO backend_app', r.sequence_name);
    END LOOP;
END $$;
-- Yönetici (testler, elle müdahale) public'te tablo açarsa backend yine de erişebilsin.
ALTER DEFAULT PRIVILEGES FOR ROLE CURRENT_USER IN SCHEMA public GRANT ALL ON TABLES TO backend_app;
ALTER DEFAULT PRIVILEGES FOR ROLE CURRENT_USER IN SCHEMA public GRANT ALL ON SEQUENCES TO backend_app;

-- ---------------------------------------------------------------- iyzico → iyzico_app
CREATE SCHEMA IF NOT EXISTS iyzico AUTHORIZATION iyzico_app;
ALTER SCHEMA iyzico OWNER TO iyzico_app;
REVOKE ALL ON SCHEMA iyzico FROM PUBLIC;
DO $$
DECLARE r record;
BEGIN
    FOR r IN SELECT tablename FROM pg_tables WHERE schemaname = 'iyzico' LOOP
        EXECUTE format('ALTER TABLE iyzico.%I OWNER TO iyzico_app', r.tablename);
    END LOOP;
    FOR r IN SELECT sequence_name FROM information_schema.sequences WHERE sequence_schema = 'iyzico' LOOP
        EXECUTE format('ALTER SEQUENCE iyzico.%I OWNER TO iyzico_app', r.sequence_name);
    END LOOP;
    FOR r IN SELECT t.typname FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
             WHERE n.nspname = 'iyzico' AND t.typtype = 'e' LOOP
        EXECUTE format('ALTER TYPE iyzico.%I OWNER TO iyzico_app', r.typname);
    END LOOP;
END $$;
