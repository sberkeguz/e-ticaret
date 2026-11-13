import type { INestApplication } from "@nestjs/common";
import { type ChannelModel, connect } from "amqplib";
import { afterAll, beforeAll, beforeEach, describe, expect, inject, it } from "vitest";
import { AttemptStore } from "../src/attempt/attempt.store.js";
import { OutboxRelay } from "../src/events/outbox-relay.js";
import { PAYMENTS_EXCHANGE, type PaymentEvent } from "../src/events/payment-events.js";
import { PaymentAttemptService } from "../src/payment/payment-attempt.service.js";
import { PrismaService } from "../src/prisma.service.js";
import type { FakeIyzicoGateway } from "./fake-iyzico.gateway.js";
import { createTestApp, newKey, newOrderId, paymentRequest } from "./test-app.js";

const TRACE = "a".repeat(32);
const TEST_QUEUE = "test.payment-events";

/** Outbox ve relay: olay durum değişikliğiyle birlikte yazılır, RabbitMQ'ya kaybolmadan gider. */
describe("outbox", () => {
  let app: INestApplication;
  let iyzico: FakeIyzicoGateway;
  let payments: PaymentAttemptService;
  let store: AttemptStore;
  let db: PrismaService;
  let relay: OutboxRelay;
  let broker: ChannelModel;

  beforeAll(async () => {
    // Zamanlayıcıyı çok yavaş kuruyoruz; relay'i testte elle tetikleyeceğiz.
    ({ app, iyzico } = await createTestApp({ rabbitmqUrl: inject("rabbitmqUrl"), outboxIntervalMs: 3_600_000 }));
    payments = app.get(PaymentAttemptService);
    store = app.get(AttemptStore);
    db = app.get(PrismaService);
    relay = app.get(OutboxRelay);

    // Backend'in kuyruğunu taklit eden bir test kuyruğu.
    broker = await connect(inject("rabbitmqUrl"));
    const channel = await broker.createChannel();
    await relay.publishPending(); // exchange'leri tanımlatır
    await channel.assertQueue(TEST_QUEUE, { durable: true });
    await channel.bindQueue(TEST_QUEUE, PAYMENTS_EXCHANGE, "payment.#");
    await channel.close();
  });
  afterAll(async () => {
    await broker.close();
    await app.close();
  });
  beforeEach(async () => {
    iyzico.reset();
    await relay.publishPending(); // önceki testlerden kalanları boşalt
    const channel = await broker.createChannel();
    await channel.purgeQueue(TEST_QUEUE);
    await channel.close();
  });

  it("başarılı ödeme, aynı işlemde tek bir kartsız PaymentSucceeded olayı yazar", async () => {
    const paid = (await payments.start(newKey(), TRACE, paymentRequest(newOrderId(), 42.5))).view;

    const rows = await outboxFor(paid.attemptId);
    expect(rows.map((row) => row.type)).toEqual(["PaymentSucceeded"]);
    const event = rows[0].payload as unknown as PaymentEvent;
    expect(event.version).toBe(1);
    expect(event.traceId).toBe(TRACE);
    expect(event.data).toMatchObject({ attemptId: paid.attemptId, status: "SUCCEEDED", amount: "42.50", currency: "TRY" });
    expect(JSON.stringify(event)).not.toContain("5528790000000008");
    expect(rows[0].publishedAt).toBeNull();
  });

  it("ara durumlar olay üretmez; reddedilen ödeme PaymentFailed üretir", async () => {
    const started = (await payments.start(newKey(), TRACE, paymentRequest(newOrderId(), 10, true))).view;
    expect(await outboxFor(started.attemptId)).toHaveLength(0); // AWAITING_3DS

    const failed = await payments.completeThreeDS(started.attemptId, {
      status: "failure", paymentId: started.iyzicoPaymentId ?? undefined, conversationId: started.attemptId, mdStatus: "0",
    });
    expect(failed.status).toBe("FAILED");
    expect((await outboxFor(started.attemptId)).map((row) => row.type)).toEqual(["PaymentFailed"]);
  });

  it("geri alınan geçiş olay da bırakmaz", async () => {
    const orderId = newOrderId();
    await payments.start(newKey(), TRACE, paymentRequest(orderId, 10));
    const second = await store.create({ orderId, idempotencyKey: newKey(), requestHash: "x".repeat(64), amount: "10.00", threeDS: false, traceId: TRACE });
    await store.move(second.id, "CREATED", { to: "SENT", note: "test" });

    // "Siparişte tek başarılı ödeme" kuralı bu geçişi reddeder; işlemle birlikte olay da yazılmaz.
    await expect(store.move(second.id, "SENT", { to: "SUCCEEDED", note: "test" })).rejects.toBeTruthy();
    expect(await outboxFor(second.id)).toHaveLength(0);
  });

  it("relay olayı RabbitMQ'ya gönderir, onay gelince gönderildi diye işaretler", async () => {
    const paid = (await payments.start(newKey(), TRACE, paymentRequest(newOrderId(), 15))).view;

    expect(await relay.publishPending()).toBeGreaterThanOrEqual(1);

    const [row] = await outboxFor(paid.attemptId);
    expect(row.publishedAt).not.toBeNull();
    const message = await receiveOne();
    expect(message.fields.routingKey).toBe("payment.succeeded");
    expect(message.properties.messageId).toBe(row.id);
    expect(message.properties.type).toBe("PaymentSucceeded");
    expect(message.properties.deliveryMode).toBe(2);
    // Olay, ödemeyi başlatan isteğin izine bağlı (backend dinleyicisi aynı traceId ile devam eder).
    expect(message.properties.headers?.traceparent).toMatch(new RegExp(`^00-${TRACE}-[0-9a-f]{16}-01$`));
    expect(JSON.parse(message.content.toString()).data.attemptId).toBe(paid.attemptId);
  });

  it("RabbitMQ'ya ulaşılamazsa olay outbox'ta bekler, bağlantı gelince gönderilir", async () => {
    const paid = (await payments.start(newKey(), TRACE, paymentRequest(newOrderId(), 20))).view;
    const goodUrl = inject("rabbitmqUrl");

    await setRabbitUrl(relay, "amqp://guest:guest@127.0.0.1:1");
    expect(await relay.publishPending()).toBe(0);
    expect((await outboxFor(paid.attemptId))[0].publishedAt).toBeNull();

    await setRabbitUrl(relay, goodUrl);
    expect(await relay.publishPending()).toBeGreaterThanOrEqual(1);
    expect((await outboxFor(paid.attemptId))[0].publishedAt).not.toBeNull();
  });

  async function outboxFor(attemptId: string) {
    const rows = await db.outboxMessage.findMany({ orderBy: { createdAt: "asc" } });
    return rows.filter((row) => (row.payload as unknown as PaymentEvent).data.attemptId === attemptId);
  }

  async function receiveOne() {
    const channel = await broker.createChannel();
    try {
      for (let i = 0; i < 50; i++) {
        const message = await channel.get(TEST_QUEUE, { noAck: true });
        if (message) {
          return message;
        }
        await new Promise((resolve) => setTimeout(resolve, 100));
      }
      throw new Error("Mesaj gelmedi");
    } finally {
      await channel.close();
    }
  }
});

/** Testte relay'in bağlandığı adresi değiştirir (kapalı RabbitMQ'yu taklit etmek için). */
async function setRabbitUrl(relay: OutboxRelay, url: string): Promise<void> {
  const internals = relay as unknown as { config: { rabbitmqUrl: string }; disconnect(): Promise<void> };
  await internals.disconnect();
  internals.config.rabbitmqUrl = url;
}
