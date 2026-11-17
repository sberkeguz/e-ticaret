-- Rezervasyon (stok tutma) süresinin başladığı an. Sipariş açılınca başlar; ödeme reddedilince
-- yeniden başlar ki müşteri ret ne kadar geç gelirse gelsin tekrar deneyebilsin.
ALTER TABLE payments ADD COLUMN reservation_started_at TIMESTAMPTZ;
UPDATE payments SET reservation_started_at = created_at;
ALTER TABLE payments ALTER COLUMN reservation_started_at SET NOT NULL;

DROP INDEX IF EXISTS ix_payments_pending_created;
CREATE INDEX ix_payments_pending_reservation ON payments (reservation_started_at) WHERE status = 'PENDING';
