-- CreateSchema
CREATE SCHEMA IF NOT EXISTS "iyzico";

-- CreateEnum
CREATE TYPE "AttemptStatus" AS ENUM ('CREATED', 'SENT', 'AWAITING_3DS', 'CONFIRM_SENT', 'SUCCEEDED', 'FAILED', 'VERIFYING', 'REFUNDED');

-- CreateTable
CREATE TABLE "payment_attempts" (
    "id" UUID NOT NULL,
    "order_id" BIGINT NOT NULL,
    "idempotency_key" VARCHAR(100) NOT NULL,
    "request_hash" VARCHAR(64) NOT NULL,
    "amount" DECIMAL(12,2) NOT NULL,
    "currency" VARCHAR(3) NOT NULL,
    "three_ds" BOOLEAN NOT NULL,
    "status" "AttemptStatus" NOT NULL,
    "iyzico_payment_id" VARCHAR(50),
    "failure_reason" VARCHAR(500),
    "trace_id" VARCHAR(32) NOT NULL,
    "created_at" TIMESTAMPTZ(3) NOT NULL,
    "updated_at" TIMESTAMPTZ(3) NOT NULL,

    CONSTRAINT "payment_attempts_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "payment_attempt_events" (
    "id" BIGSERIAL NOT NULL,
    "attempt_id" UUID NOT NULL,
    "from_status" "AttemptStatus",
    "to_status" "AttemptStatus" NOT NULL,
    "note" VARCHAR(500),
    "occurred_at" TIMESTAMPTZ(3) NOT NULL,

    CONSTRAINT "payment_attempt_events_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "uq_attempt_idempotency_key" ON "payment_attempts"("idempotency_key");

-- CreateIndex
CREATE UNIQUE INDEX "uq_attempt_iyzico_payment_id" ON "payment_attempts"("iyzico_payment_id");

-- CreateIndex
CREATE INDEX "ix_attempt_order" ON "payment_attempts"("order_id");

-- CreateIndex
CREATE INDEX "ix_attempt_event_attempt" ON "payment_attempt_events"("attempt_id");

-- AddForeignKey
ALTER TABLE "payment_attempt_events" ADD CONSTRAINT "payment_attempt_events_attempt_id_fkey" FOREIGN KEY ("attempt_id") REFERENCES "payment_attempts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;


-- ---------------------------------------------------------------------------
-- Elle eklendi: Prisma şeması kısmi indeksleri ifade edemiyor.
-- ---------------------------------------------------------------------------

-- Kural 1: bir siparişin aynı anda en fazla BİR aktif denemesi olur.
CREATE UNIQUE INDEX "uq_attempt_one_active_per_order" ON "payment_attempts"("order_id")
    WHERE "status" IN ('CREATED', 'SENT', 'AWAITING_3DS', 'CONFIRM_SENT', 'VERIFYING');

-- Kural 2: bir siparişin en fazla BİR başarılı ödemesi olur.
CREATE UNIQUE INDEX "uq_attempt_one_success_per_order" ON "payment_attempts"("order_id")
    WHERE "status" = 'SUCCEEDED';
