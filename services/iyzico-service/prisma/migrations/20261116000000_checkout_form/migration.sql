-- iyzico Ödeme Formu (Checkout Form): kart bilgisini müşteri iyzico'nun formuna girer, bize
-- yalnızca formun token'ı gelir. Token, dönüşte ve mutabakatta sonucu iyzico'ya sormak için tutulur.
ALTER TABLE "iyzico"."payment_attempts" ADD COLUMN "checkout_token" VARCHAR(100);
CREATE UNIQUE INDEX "uq_attempt_checkout_token" ON "iyzico"."payment_attempts" ("checkout_token");
