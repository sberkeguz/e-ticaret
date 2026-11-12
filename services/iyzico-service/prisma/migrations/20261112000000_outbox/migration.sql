-- CreateTable
CREATE TABLE "outbox_messages" (
    "id" UUID NOT NULL,
    "exchange" VARCHAR(100) NOT NULL,
    "routing_key" VARCHAR(100) NOT NULL,
    "type" VARCHAR(100) NOT NULL,
    "payload" JSONB NOT NULL,
    "created_at" TIMESTAMPTZ(3) NOT NULL,
    "published_at" TIMESTAMPTZ(3),
    "attempts" INTEGER NOT NULL DEFAULT 0,
    "last_error" VARCHAR(500),

    CONSTRAINT "outbox_messages_pkey" PRIMARY KEY ("id")
);


-- Elle eklendi: relay yalnızca gönderilmemiş satırlara bakar; bu indeks onları hızlı bulur.
CREATE INDEX "ix_outbox_unpublished" ON "outbox_messages"("created_at") WHERE "published_at" IS NULL;
