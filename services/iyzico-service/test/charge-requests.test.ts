import type { INestApplication } from "@nestjs/common";
import { randomUUID } from "node:crypto";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { type ChargeRequested, ChargeRequestsConsumer } from "../src/events/charge-requests.consumer.js";
import type { PaymentEvent } from "../src/events/payment-events.js";
import { PrismaService } from "../src/prisma.service.js";
import type { FakeIyzicoGateway } from "./fake-iyzico.gateway.js";
import { createTestApp, newKey, newOrderId, paymentBody } from "./test-app.js";

/** Backend'in RabbitMQ ile gönderdiği 2D ödeme isteği (komut). */
describe("kuyruktan gelen 2D ödeme isteği", () => {
  let app: INestApplication;
  let iyzico: FakeIyzicoGateway;
  let consumer: ChargeRequestsConsumer;
  let db: PrismaService;

  beforeAll(async () => {
    ({ app, iyzico } = await createTestApp());
    consumer = app.get(ChargeRequestsConsumer);
    db = app.get(PrismaService);
  });
  afterAll(() => app.close());
  beforeEach(() => iyzico.reset());

  const command = (orderId: number, overrides: Partial<ChargeRequested> = {}): ChargeRequested => ({
    type: "ChargeRequested",
    version: 1,
    attemptId: randomUUID(),
    idempotencyKey: newKey(),
    deadline: new Date(Date.now() + 50_000).toISOString(),
    request: paymentBody(orderId, 40),
    ...overrides,
  });

  it("iyzico'ya gönderir, backend'in verdiği deneme numarasıyla PaymentSucceeded yayınlar", async () => {
    const message = command(newOrderId());

    await consumer.process(message);

    const attempt = await db.paymentAttempt.findUnique({ where: { id: message.attemptId } });
    expect(attempt?.status).toBe("SUCCEEDED");
    expect(iyzico.charges).toBe(1);
    expect(await eventTypes(message.attemptId)).toEqual(["PaymentSucceeded"]);
  });

  it("aynı mesaj iki kez gelirse kart bir kez çekilir", async () => {
    const message = command(newOrderId());

    await consumer.process(message);
    await consumer.process(message);

    expect(iyzico.charges).toBe(1);
    expect(await eventTypes(message.attemptId)).toEqual(["PaymentSucceeded"]);
  });

  it("ilk teslim hâlâ işlenirken aynı mesaj tekrar gelirse hata vermez (sonsuz tekrar olmaz), kart bir kez çekilir", async () => {
    iyzico.delayMs = 400;
    const message = command(newOrderId());

    const first = consumer.process(message);
    await new Promise((resolve) => setTimeout(resolve, 100));
    await expect(consumer.process(message)).resolves.toBeUndefined();
    await first;

    expect(iyzico.charges).toBe(1);
    const attempt = await db.paymentAttempt.findUnique({ where: { id: message.attemptId } });
    expect(attempt?.status).toBe("SUCCEEDED");
  });

  it("süresi geçmiş istek iyzico'ya gönderilmez, PaymentFailed yayınlanır", async () => {
    const message = command(newOrderId(), { deadline: new Date(Date.now() - 1_000).toISOString() });

    await consumer.process(message);
    await consumer.process(message); // tekrar gelirse yine bir şey olmaz

    expect(iyzico.charges).toBe(0);
    const attempt = await db.paymentAttempt.findUnique({ where: { id: message.attemptId } });
    expect(attempt?.status).toBe("FAILED");
    expect(attempt?.failureReason).toContain("zamanında");
    expect(await eventTypes(message.attemptId)).toEqual(["PaymentFailed"]);
  });

  it("geçersiz istek iyzico'ya gönderilmez, sebebiyle PaymentFailed yayınlanır", async () => {
    const body = { ...paymentBody(newOrderId(), 40), card: undefined };
    const message = command(body.orderId, { request: body });

    await consumer.process(message);

    expect(iyzico.charges).toBe(0);
    const attempt = await db.paymentAttempt.findUnique({ where: { id: message.attemptId } });
    expect(attempt?.status).toBe("FAILED");
    expect(attempt?.failureReason).toContain("Geçersiz ödeme isteği");
  });

  it("iyzico'ya bağlanılamazsa müşteriye teknik ayrıntı değil anlaşılır sebep gider", async () => {
    iyzico.unreachable = true;
    const message = command(newOrderId());

    await consumer.process(message);

    const attempt = await db.paymentAttempt.findUnique({ where: { id: message.attemptId } });
    expect(attempt?.status).toBe("FAILED");
    expect(attempt?.failureReason).toContain("para çekilmedi");
    expect(attempt?.failureReason).not.toContain("ECONNREFUSED");
  });

  it("reddedilen kart PaymentFailed olur", async () => {
    iyzico.declineCharges = true;
    const message = command(newOrderId());

    await consumer.process(message);

    expect(await eventTypes(message.attemptId)).toEqual(["PaymentFailed"]);
  });

  async function eventTypes(attemptId: string): Promise<string[]> {
    const rows = await db.outboxMessage.findMany({ orderBy: { createdAt: "asc" } });
    return rows
      .filter((row) => (row.payload as unknown as PaymentEvent).data.attemptId === attemptId)
      .map((row) => row.type);
  }
});
