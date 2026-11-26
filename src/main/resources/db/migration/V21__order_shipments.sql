-- Siparişin kargo gönderisi (sipariş başına en çok bir satır). Gönderiyi cargo-service yürütür; bu tablo
-- backend'in bildiği son durumdur ve cargo-service'in olaylarıyla güncellenir.
-- Alıcı bilgisi (ad, telefon, adres) ve kargo firmasının ham hata metni burada TUTULMAZ: alıcı bilgisi
-- siparişte zaten vardır, kargo komutuyla gider; hata yalnızca sabit bir kodla (failure_code) saklanır.
--   request_no          : kaçıncı kargo isteği (1'den başlar, "Tekrar dene" bir artırır)
--   last_event_sequence : uygulanan son olayın sırası; eski/tekrar gelen olay uygulanmaz
CREATE TABLE order_shipments (
    id                  BIGSERIAL     PRIMARY KEY,
    order_id            BIGINT        NOT NULL UNIQUE REFERENCES orders (id),
    request_no          INTEGER       NOT NULL CHECK (request_no >= 1),
    status              VARCHAR(20)   NOT NULL
        CHECK (status IN ('REQUESTED', 'CREATED', 'IN_TRANSIT', 'DELIVERED', 'DELIVERY_FAILED', 'FAILED')),
    cargo_shipment_id   UUID,
    carrier_code        VARCHAR(50),
    carrier_name        VARCHAR(100),
    tracking_number     VARCHAR(100),
    failure_code        VARCHAR(40),
    status_at           TIMESTAMPTZ   NOT NULL,
    last_event_sequence INTEGER       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Takılı kalan istekleri (uzun süredir REQUESTED) bulmak için.
CREATE INDEX ix_order_shipments_status_status_at ON order_shipments (status, status_at);
