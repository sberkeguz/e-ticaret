import type { INestApplication } from "@nestjs/common";
import { type ChannelModel, connect } from "amqplib";
import { afterAll, beforeAll, describe, expect, inject, it } from "vitest";
import { OUTBOX_CHANNEL, OutboxRelay } from "../src/events/outbox-relay.js";
import { PAYMENTS_EXCHANGE, type PaymentEvent } from "../src/events/payment-events.js";
import { PaymentAttemptService } from "../src/payment/payment-attempt.service.js";
import { PrismaService } from "../src/prisma.service.js";
import { createTestApp, newKey, newOrderId, paymentRequest } from "./test-app.js";

const TRACE = "d".repeat(32);
const QUEUE = "test.payment-events.listen";

/**
 * Olay, saniyede bir yapılan kontrolü beklemeden gider: veritabanı yeni outbox satırını
 * (işlem kaydedilince) haber verir, relay hemen yayınlar. Zamanlayıcı burada 1 saate ayarlı;
 * yani olay gidiyorsa, haber sayesinde gidiyordur.
 */
describe("outbox anında gönderim (LISTEN/NOTIFY)", () => {
  let app: INestApplication;
  let payments: PaymentAttemptService;
  let db: PrismaService;
  let broker: ChannelModel;

  beforeAll(async () => {
    ({ app } = await createTestApp({ rabbitmqUrl: inject("rabbitmqUrl"), outboxIntervalMs: 3_600_000, outboxListen: true }));
    payments = app.get(PaymentAttemptService);
    db = app.get(PrismaService);
    await app.get(OutboxRelay).publishPending(); // exchange'leri tanımlatır, eskileri boşaltır
    broker = await connect(inject("rabbitmqUrl"));
    const channel = await broker.createChannel();
    await channel.assertQueue(QUEUE, { durable: true });
    await channel.bindQueue(QUEUE, PAYMENTS_EXCHANGE, "payment.#");
    await channel.purgeQueue(QUEUE);
    await channel.close();
    await waitFor(async () => (await listeners()) > 0, 5_000);
  });
  afterAll(async () => {
    await broker.close();
    await app.close();
  });

  it("başarılı ödemenin olayı 1 saniyeden kısa sürede RabbitMQ'da", async () => {
    const started = Date.now();
    const paid = (await payments.start(newKey(), TRACE, paymentRequest(newOrderId(), 12))).view;

    const event = await receiveFor(paid.attemptId, 1_000);
    expect(event.type).toBe("PaymentSucceeded");
    expect(Date.now() - started).toBeLessThan(1_000);
  });

  it("dinleme bağlantısı koparsa yeniden bağlanır, anında gönderim devam eder", async () => {
    await db.$executeRawUnsafe(
      `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE query = 'LISTEN ${OUTBOX_CHANNEL}' AND pid <> pg_backend_pid()`,
    );
    await waitFor(async () => (await listeners()) === 0, 2_000);
    await waitFor(async () => (await listeners()) > 0, 10_000); // 5 sn sonra yeniden bağlanır

    const paid = (await payments.start(newKey(), TRACE, paymentRequest(newOrderId(), 13))).view;
    expect((await receiveFor(paid.attemptId, 1_000)).type).toBe("PaymentSucceeded");
  });

  /** Veritabanında şu an dinleyen bağlantı sayısı. */
  async function listeners(): Promise<number> {
    const rows = await db.$queryRawUnsafe<{ n: bigint }[]>(
      `SELECT count(*) AS n FROM pg_stat_activity WHERE query = 'LISTEN ${OUTBOX_CHANNEL}'`,
    );
    return Number(rows[0].n);
  }

  async function receiveFor(attemptId: string, withinMs: number): Promise<PaymentEvent> {
    const channel = await broker.createChannel();
    const deadline = Date.now() + withinMs;
    try {
      while (Date.now() < deadline) {
        const message = await channel.get(QUEUE, { noAck: true });
        if (message) {
          const event = JSON.parse(message.content.toString()) as PaymentEvent;
          if (event.data.attemptId === attemptId) {
            return event;
          }
          continue;
        }
        await new Promise((resolve) => setTimeout(resolve, 20));
      }
      throw new Error(`${withinMs} ms içinde olay gelmedi`);
    } finally {
      await channel.close();
    }
  }
});

async function waitFor(condition: () => Promise<boolean>, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await condition()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("Beklenen durum oluşmadı");
}
