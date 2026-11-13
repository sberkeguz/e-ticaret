-- Outbox'a yeni olay yazılınca gönderen işe haber ver (LISTEN/NOTIFY). PostgreSQL bu haberi
-- ancak işlem kaydedildikten (COMMIT) sonra iletir; geri alınan işlem haber vermez.
-- Böylece olay, saniyede bir yapılan kontrolü beklemeden hemen RabbitMQ'ya gider.
CREATE OR REPLACE FUNCTION "iyzico"."notify_outbox"() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_notify('iyzico_outbox', '');
    RETURN NULL;
END
$$;

CREATE TRIGGER "outbox_messages_notify"
    AFTER INSERT ON "iyzico"."outbox_messages"
    FOR EACH STATEMENT EXECUTE FUNCTION "iyzico"."notify_outbox"();
