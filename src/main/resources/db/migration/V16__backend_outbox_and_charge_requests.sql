-- 2D ödeme isteği komut olarak iyzico-service'e RabbitMQ ile gider (backend iyzico-service'e HTTP
-- isteği atmaz). Komut, siparişle aynı işlemde bu tabloya yazılır (transactional outbox); ayrı bir iş
-- RabbitMQ'ya iletir. İletildikten sonra komuttaki kart bilgisi silinir.
CREATE TABLE outbox_messages (
    id           UUID          PRIMARY KEY,
    exchange     VARCHAR(100)  NOT NULL,
    routing_key  VARCHAR(100)  NOT NULL,
    type         VARCHAR(100)  NOT NULL,
    payload      TEXT          NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL,
    published_at TIMESTAMPTZ,
    attempts     INTEGER       NOT NULL DEFAULT 0,
    last_error   VARCHAR(500)
);
CREATE INDEX ix_outbox_messages_unpublished ON outbox_messages (created_at) WHERE published_at IS NULL;

-- Kuyruktan giden 2D isteğinin backend'in bildiği son durumu (iyzico-service'e sorulmaz; olaylardan gelir):
-- REQUESTED (gönderildi, sonuç bekleniyor) → SUCCEEDED / FAILED / VERIFYING.
-- charge_deadline: iyzico-service bu andan sonra gelen isteği iyzico'ya göndermez (geç çekim olmasın).
ALTER TABLE payments
    ADD COLUMN last_attempt_status VARCHAR(30),
    ADD COLUMN failure_reason      VARCHAR(500),
    ADD COLUMN charge_deadline     TIMESTAMPTZ;
