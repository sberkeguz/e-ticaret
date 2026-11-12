-- Admin'e düşen ödeme uyarıları: fazladan alınan ve otomatik iade edilen (ya da edilemeyen) ödemeler.
CREATE TABLE payment_alerts (
    id                 BIGSERIAL     PRIMARY KEY,
    kind               VARCHAR(40)   NOT NULL,  -- SECOND_PAYMENT | LATE_PAYMENT | ORPHAN_PAYMENT
    order_id           BIGINT        NOT NULL,
    attempt_id         UUID          NOT NULL,
    iyzico_payment_id  VARCHAR(50),
    amount             NUMERIC(12,2),
    refunded           BOOLEAN       NOT NULL,
    message            VARCHAR(500)  NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    resolved_at        TIMESTAMPTZ,
    CONSTRAINT uq_payment_alert_attempt UNIQUE (attempt_id)  -- aynı fazla ödeme için tek uyarı
);
CREATE INDEX ix_payment_alert_open ON payment_alerts (created_at DESC) WHERE resolved_at IS NULL;
