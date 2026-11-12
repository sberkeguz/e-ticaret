import type { INestApplication } from "@nestjs/common";
import request from "supertest";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { PaymentEvent } from "../src/events/payment-events.js";
import { BackgroundWork } from "../src/payment/background-work.js";
import { PrismaService } from "../src/prisma.service.js";
import type { FakeIyzicoGateway } from "./fake-iyzico.gateway.js";
import { createTestApp, newKey, newOrderId, paymentBody, TOKEN } from "./test-app.js";

/** Adım 3: 2D ödeme "Prefer: respond-async" ile hemen kabul edilir, sonuç arka planda belli olur. */
describe("asenkron 2D ödeme", () => {
  let app: INestApplication;
  let iyzico: FakeIyzicoGateway;
  let background: BackgroundWork;
  let db: PrismaService;

  beforeAll(async () => {
    ({ app, iyzico } = await createTestApp());
    background = app.get(BackgroundWork);
    db = app.get(PrismaService);
  });
  afterAll(() => app.close());
  beforeEach(() => iyzico.reset());

  const startAsync = (key: string, body: object) =>
    request(app.getHttpServer()).post("/payments")
      .set("X-Internal-Token", TOKEN).set("Idempotency-Key", key).set("Prefer", "respond-async").send(body);

  const attempt = (id: string) =>
    request(app.getHttpServer()).get(`/payments/${id}`).set("X-Internal-Token", TOKEN);

  it("202 ile hemen kabul eder, para çekme arka planda olur ve olay yazılır", async () => {
    iyzico.delayMs = 300;

    const accepted = await startAsync(newKey(), paymentBody(newOrderId(), 25));

    expect(accepted.status).toBe(202);
    expect(accepted.headers["preference-applied"]).toBe("respond-async");
    expect(accepted.headers.location).toBe(`/payments/${accepted.body.attemptId}`);
    expect(accepted.body.status).toBe("CREATED"); // iyzico'yu beklemeden döndü

    await background.idle();
    expect((await attempt(accepted.body.attemptId)).body.status).toBe("SUCCEEDED");
    expect(iyzico.charges).toBe(1);
    expect(await eventTypes(accepted.body.attemptId)).toEqual(["PaymentSucceeded"]);
  });

  it("işlem sürerken aynı anahtarla tekrar gelirse aynı deneme döner, kart bir kez çekilir", async () => {
    iyzico.delayMs = 500;
    const key = newKey();
    const body = paymentBody(newOrderId(), 30);

    const first = await startAsync(key, body);
    const again = await startAsync(key, body);

    expect(again.status).toBe(202);
    expect(again.headers["idempotent-replayed"]).toBe("true");
    expect(again.body.attemptId).toBe(first.body.attemptId);
    await background.idle();
    expect(iyzico.charges).toBe(1);
  });

  it("reddedilen kart arka planda FAILED olur ve PaymentFailed olayı çıkar", async () => {
    iyzico.declineCharges = true;

    const accepted = await startAsync(newKey(), paymentBody(newOrderId(), 12));
    await background.idle();

    const result = (await attempt(accepted.body.attemptId)).body;
    expect(result.status).toBe("FAILED");
    expect(result.failureReason).toBe("Kart limiti yetersiz");
    expect(await eventTypes(accepted.body.attemptId)).toEqual(["PaymentFailed"]);
  });

  it("arka planda cevap düşerse yine kör tekrar yok, iyzico'ya sorulur", async () => {
    iyzico.dropChargeAnswer = true;

    const accepted = await startAsync(newKey(), paymentBody(newOrderId(), 18));
    await background.idle();

    expect(iyzico.charges).toBe(1);
    expect(iyzico.inquiries).toBe(1);
    expect((await attempt(accepted.body.attemptId)).body.status).toBe("SUCCEEDED");
  });

  it("3D Secure, Prefer başlığı olsa da senkron kalır (banka sayfası hemen gerekir)", async () => {
    const response = await startAsync(newKey(), paymentBody(newOrderId(), 40, true));

    expect(response.status).toBe(201);
    expect(response.headers["preference-applied"]).toBeUndefined();
    expect(response.body.status).toBe("AWAITING_3DS");
    expect(response.body.threeDSHtmlContent).toBeTruthy();
  });

  it("kart numarası ne denemede ne olayda saklanır", async () => {
    const accepted = await startAsync(newKey(), paymentBody(newOrderId(), 22));
    await background.idle();

    const stored = JSON.stringify(await db.paymentAttempt.findUnique({ where: { id: accepted.body.attemptId } }), bigint)
      + JSON.stringify(await db.outboxMessage.findMany(), bigint);
    expect(stored).not.toContain("5528790000000008");
    expect(stored).not.toContain('"cvc"');
  });

  async function eventTypes(attemptId: string): Promise<string[]> {
    const rows = await db.outboxMessage.findMany({ orderBy: { createdAt: "asc" } });
    return rows
      .filter((row) => (row.payload as unknown as PaymentEvent).data.attemptId === attemptId)
      .map((row) => row.type);
  }
});

function bigint(_key: string, value: unknown): unknown {
  return typeof value === "bigint" ? value.toString() : value;
}
