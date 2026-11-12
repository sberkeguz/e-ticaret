import { randomUUID } from "node:crypto";
import type { PaymentAttempt, Prisma } from "../generated/prisma/client.js";
import type { AttemptStatus } from "../attempt/attempt-status.js";

/**
 * Bu servisin yayınladığı olaylar. Kart verisi İÇERMEZ.
 *
 * Sürüm kuralı: alanlar silinmez ve anlamı değişmez, yalnızca yeni alan eklenir.
 * Dinleyiciler tanımadıkları alanı görmezden gelir. Kırıcı bir değişiklik gerekirse
 * yeni bir olay tipi (ör. PaymentSucceededV2) açılır.
 */
export const PAYMENTS_EXCHANGE = "payments";
/** Hiçbir kuyruğa yönlenemeyen mesaj kaybolmasın diye buraya düşer. */
export const UNROUTED_EXCHANGE = "payments.unrouted";
export const UNROUTED_QUEUE = "payments.unrouted";
export const EVENT_VERSION = 1;

/** Hangi durum hangi olayı doğurur. Ara durumlar (CREATED, SENT…) olay üretmez. */
const EVENTS: Partial<Record<AttemptStatus, { type: string; routingKey: string }>> = {
  SUCCEEDED: { type: "PaymentSucceeded", routingKey: "payment.succeeded" },
  FAILED: { type: "PaymentFailed", routingKey: "payment.failed" },
  VERIFYING: { type: "PaymentVerifying", routingKey: "payment.verifying" },
  REFUNDED: { type: "PaymentRefunded", routingKey: "payment.refunded" },
};

export interface PaymentEvent {
  messageId: string;
  type: string;
  version: number;
  occurredAt: string;
  traceId: string;
  data: {
    attemptId: string;
    orderId: number;
    status: AttemptStatus;
    /** "12.50" gibi; para float ile taşınmaz */
    amount: string;
    currency: string;
    threeDS: boolean;
    iyzicoPaymentId: string | null;
    failureReason: string | null;
  };
}

/** Deneme bu duruma geçtiyse yazılacak outbox satırı; olay yoksa null. */
export function outboxRowFor(attempt: PaymentAttempt): Prisma.OutboxMessageCreateInput | null {
  const event = EVENTS[attempt.status];
  if (!event) {
    return null;
  }
  const messageId = randomUUID();
  const now = new Date();
  const payload: PaymentEvent = {
    messageId,
    type: event.type,
    version: EVENT_VERSION,
    occurredAt: now.toISOString(),
    traceId: attempt.traceId,
    data: {
      attemptId: attempt.id,
      orderId: Number(attempt.orderId),
      status: attempt.status,
      amount: attempt.amount.toFixed(2),
      currency: attempt.currency,
      threeDS: attempt.threeDS,
      iyzicoPaymentId: attempt.iyzicoPaymentId,
      failureReason: attempt.failureReason,
    },
  };
  return {
    id: messageId,
    exchange: PAYMENTS_EXCHANGE,
    routingKey: event.routingKey,
    type: event.type,
    payload: payload as unknown as Prisma.InputJsonValue,
    createdAt: now,
  };
}
