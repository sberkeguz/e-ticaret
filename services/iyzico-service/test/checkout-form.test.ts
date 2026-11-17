import type { INestApplication } from "@nestjs/common";
import request from "supertest";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { AttemptStore } from "../src/attempt/attempt.store.js";
import type { PaymentEvent } from "../src/events/payment-events.js";
import { Reconciler } from "../src/payment/reconciler.js";
import { PrismaService } from "../src/prisma.service.js";
import type { FakeIyzicoGateway } from "./fake-iyzico.gateway.js";
import { createTestApp, newKey, newOrderId, paymentBody, TOKEN } from "./test-app.js";

/**
 * iyzico Ödeme Formu: kart bilgisi bize hiç gelmez. Müşteri iyzico'nun formunu doldurur, iyzico
 * dönüşte yalnızca token gönderir; sonuç iyzico'ya sorulur.
 */
describe("iyzico Ödeme Formu (kartsız akış)", () => {
  let app: INestApplication;
  let iyzico: FakeIyzicoGateway;
  let store: AttemptStore;
  let db: PrismaService;
  let reconciler: Reconciler;

  beforeAll(async () => {
    ({ app, iyzico } = await createTestApp());
    store = app.get(AttemptStore);
    db = app.get(PrismaService);
    reconciler = app.get(Reconciler);
  });
  afterAll(() => app.close());
  beforeEach(() => iyzico.reset());

  const post = (path: string) => request(app.getHttpServer()).post(path).set("X-Internal-Token", TOKEN);

  /** Kartsız form isteği; "Prefer: respond-async" gönderilse bile form içeriği hemen döner. */
  async function startForm(amount = 30) {
    const { card: _card, ...body } = paymentBody(newOrderId(), amount);
    const response = await post("/payments").set("Idempotency-Key", newKey()).set("Prefer", "respond-async")
      .send({ ...body, mode: "checkout-form" });
    return response;
  }

  it("kart bilgisi olmadan başlar; form içeriği döner, deneme müşteriyi bekler", async () => {
    const response = await startForm();

    expect(response.status).toBe(201);
    expect(response.body.status).toBe("AWAITING_3DS");
    expect(response.body.checkoutFormContent).toContain("iyzipay-checkout-form");
    expect(iyzico.checkoutFormStarts).toBe(1);
    expect(iyzico.charges).toBe(0);
    const stored = await store.get(response.body.attemptId);
    expect(stored.checkoutToken).toBe(`cf-${response.body.attemptId}`);
  });

  it("kartla ödeme seçilip kart gönderilmezse 400", async () => {
    const { card: _card, ...body } = paymentBody(newOrderId(), 10);
    const response = await post("/payments").set("Idempotency-Key", newKey()).send(body);
    expect(response.status).toBe(400);
  });

  it("yanlış token'la gelen dönüş reddedilir, para durumu değişmez", async () => {
    const { body: started } = await startForm();

    const response = await post(`/payments/${started.attemptId}/checkout-form-complete`).send({ token: "baska-token" });

    expect(response.status).toBe(400);
    expect((await store.get(started.attemptId)).status).toBe("AWAITING_3DS");
    expect(iyzico.checkoutFormRetrieves).toBe(0);
  });

  it("doğru dönüşte sonuç iyzico'ya sorulur: ödeme alınır, olay yazılır; ikinci dönüş bir şey yapmaz", async () => {
    const { body: started } = await startForm(45);
    const token = `cf-${started.attemptId}`;

    const first = await post(`/payments/${started.attemptId}/checkout-form-complete`).send({ token });
    const second = await post(`/payments/${started.attemptId}/checkout-form-complete`).send({ token });

    expect(first.body.status).toBe("SUCCEEDED");
    expect(first.body.iyzicoPaymentId).toBe(`cf-pay-${started.attemptId}`);
    expect(second.body.status).toBe("SUCCEEDED");
    expect(iyzico.checkoutFormRetrieves).toBe(1);
    expect(await eventTypes(started.attemptId)).toEqual(["PaymentSucceeded"]);
  });

  it("kart reddedildiyse deneme başarısız olur", async () => {
    iyzico.checkoutFormResult = "DECLINED";
    const { body: started } = await startForm();

    const response = await post(`/payments/${started.attemptId}/checkout-form-complete`).send({ token: `cf-${started.attemptId}` });

    expect(response.body.status).toBe("FAILED");
    expect(response.body.failureReason).toBe("Kart reddedildi");
  });

  it("dönüşü bize hiç ulaşmayan ama ödenmiş form, mutabakatta yakalanır", async () => {
    const { body: started } = await startForm();
    await age(started.attemptId, 20);

    await reconciler.runOnce();

    expect((await store.get(started.attemptId)).status).toBe("SUCCEEDED");
    expect(await eventTypes(started.attemptId)).toEqual(["PaymentSucceeded"]);
  });

  it("açık bırakılan form, süresi dolana kadar beklenir; dolunca kapanır", async () => {
    iyzico.checkoutFormResult = "UNCERTAIN"; // müşteri formu doldurmadı
    const { body: started } = await startForm();

    await age(started.attemptId, 20);
    await reconciler.runOnce();
    expect((await store.get(started.attemptId)).status).toBe("AWAITING_3DS");

    await age(started.attemptId, 40);
    await reconciler.runOnce();
    expect((await store.get(started.attemptId)).status).toBe("FAILED");
    expect(iyzico.charges).toBe(0);
  });

  async function age(id: string, minutesAgo: number): Promise<void> {
    await db.$executeRaw`UPDATE "iyzico"."payment_attempts" SET updated_at = now() - make_interval(mins => ${minutesAgo}) WHERE id = ${id}::uuid`;
  }

  async function eventTypes(id: string): Promise<string[]> {
    const rows = await db.outboxMessage.findMany({ orderBy: { createdAt: "asc" } });
    return rows.filter((row) => (row.payload as unknown as PaymentEvent).data.attemptId === id).map((row) => row.type);
  }
});
