import type { INestApplication } from "@nestjs/common";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { ApiProblem } from "../src/api/api-problem.js";
import type { PaymentAttemptView, ThreeDSCallback } from "../src/api/payment.dto.js";
import { AttemptStore, isUniqueViolation } from "../src/attempt/attempt.store.js";
import { PaymentAttemptService, type StartResult } from "../src/payment/payment-attempt.service.js";
import type { FakeIyzicoGateway } from "./fake-iyzico.gateway.js";
import { createTestApp, newKey, newOrderId, paymentRequest } from "./test-app.js";

const TRACE = "t".repeat(32);

/** Çift ödemeye karşı önlemlerin senaryoları. */
describe("çift ödeme önlemleri", () => {
  let app: INestApplication;
  let iyzico: FakeIyzicoGateway;
  let payments: PaymentAttemptService;
  let store: AttemptStore;

  beforeAll(async () => {
    ({ app, iyzico } = await createTestApp());
    payments = app.get(PaymentAttemptService);
    store = app.get(AttemptStore);
  });
  afterAll(() => app.close());
  beforeEach(() => iyzico.reset());

  it("aynı anahtar iki kez gelirse kart bir kez çekilir, ikincisine kayıtlı sonuç döner", async () => {
    const orderId = newOrderId();
    const key = newKey();

    const first = await payments.start(key, TRACE, paymentRequest(orderId, 100));
    const second = await payments.start(key, TRACE, paymentRequest(orderId, 100));

    expect(first.view.status).toBe("SUCCEEDED");
    expect(first.replayed).toBe(false);
    expect(second.replayed).toBe(true);
    expect(second.view.attemptId).toBe(first.view.attemptId);
    expect(iyzico.charges).toBe(1);
  });

  it("aynı anahtar farklı içerikle gelirse reddedilir", async () => {
    const orderId = newOrderId();
    const key = newKey();
    await payments.start(key, TRACE, paymentRequest(orderId, 100));

    await expect(payments.start(key, TRACE, paymentRequest(orderId, 250))).rejects.toMatchObject({ type: "idempotency-key-reused" });
    expect(iyzico.charges).toBe(1);
  });

  it("aynı anahtarla 8 kez aynı anda 'Öde' basılırsa kart bir kez çekilir", async () => {
    const orderId = newOrderId();
    const key = newKey();
    iyzico.delayMs = 300;

    const results = await concurrently(8, () => payments.start(key, TRACE, paymentRequest(orderId, 50)));

    expect(iyzico.charges).toBe(1);
    expect(results.some((r) => !(r instanceof Error))).toBe(true);
    for (const r of results) {
      if (r instanceof Error) {
        expect(r).toBeInstanceOf(ApiProblem);
        expect((r as ApiProblem).type).toBe("payment-in-progress");
      }
    }
  });

  it("farklı anahtarlarla 8 kez aynı anda 'Öde' basılırsa da kart bir kez çekilir", async () => {
    const orderId = newOrderId();
    iyzico.delayMs = 300;

    const results = await concurrently(8, () => payments.start(newKey(), TRACE, paymentRequest(orderId, 50)));

    // Bir deneme para çekme aşamasına geçince diğerleri ya reddedilir ya da başlamadan iptal edilir.
    expect(iyzico.charges).toBe(1);
    const succeeded = results.filter((r) => !(r instanceof Error) && (r as StartResult).view.status === "SUCCEEDED");
    expect(succeeded).toHaveLength(1);
  });

  it("cevap yolda düşerse istek tekrar gönderilmez, iyzico'ya sorulup sonuç bulunur", async () => {
    iyzico.dropChargeAnswer = true;

    const result = await payments.start(newKey(), TRACE, paymentRequest(newOrderId(), 75));

    expect(iyzico.charges).toBe(1);
    expect(iyzico.inquiries).toBe(1);
    expect(result.view.status).toBe("SUCCEEDED");
  });

  it("sonucu belirsiz ödeme, sipariş için yeni denemeyi engeller", async () => {
    const orderId = newOrderId();
    iyzico.dropChargeAnswer = true;
    iyzico.inquiryResult = "UNCERTAIN";

    const result = await payments.start(newKey(), TRACE, paymentRequest(orderId, 75));
    expect(result.view.status).toBe("VERIFYING");

    iyzico.dropChargeAnswer = false;
    await expect(payments.start(newKey(), TRACE, paymentRequest(orderId, 75))).rejects.toMatchObject({ type: "order-has-active-attempt" });
    expect(iyzico.charges).toBe(1);
  });

  it("3D dönüşü iki kez aynı anda gelirse para bir kez çekilir", async () => {
    const started = (await payments.start(newKey(), TRACE, paymentRequest(newOrderId(), 120, true))).view;
    expect(started.status).toBe("AWAITING_3DS");
    expect(started.threeDSHtmlContent).toBeTruthy();
    iyzico.delayMs = 300;

    const results = await concurrently(2, () => payments.completeThreeDS(started.attemptId, callback(started)));

    expect(iyzico.threeDSCompletions).toBe(1);
    expect(results.every((r) => !(r instanceof Error))).toBe(true);
    expect((await store.get(started.attemptId)).status).toBe("SUCCEEDED");

    // Çok sonra üçüncü kez gelirse yine bir şey olmaz.
    expect((await payments.completeThreeDS(started.attemptId, callback(started))).status).toBe("SUCCEEDED");
    expect(iyzico.threeDSCompletions).toBe(1);
  });

  it("imzası bozuk veya başka ödemeye ait 3D dönüşü reddedilir", async () => {
    const started = (await payments.start(newKey(), TRACE, paymentRequest(newOrderId(), 120, true))).view;

    await expect(payments.completeThreeDS(started.attemptId, { ...callback(started), paymentId: "baska-odeme" }))
      .rejects.toMatchObject({ type: "callback-rejected" });

    iyzico.callbackSignatureValid = false;
    await expect(payments.completeThreeDS(started.attemptId, callback(started))).rejects.toMatchObject({ type: "callback-rejected" });

    expect(iyzico.threeDSCompletions).toBe(0);
    expect((await store.get(started.attemptId)).status).toBe("AWAITING_3DS");
  });

  it("yeni deneme terk edilmiş 3D'yi iptal eder; eskisinin geç dönüşü para çekemez", async () => {
    const orderId = newOrderId();
    const abandoned = (await payments.start(newKey(), TRACE, paymentRequest(orderId, 60, true))).view;

    // Kullanıcı SMS ekranını kapatıp tekrar ödüyor.
    const retry = (await payments.start(newKey(), TRACE, paymentRequest(orderId, 60))).view;
    expect(retry.status).toBe("SUCCEEDED");
    expect((await store.get(abandoned.attemptId)).status).toBe("FAILED");

    // Eski SMS ekranının dönüşü geç geliyor: para çekilmemeli.
    const late = await payments.completeThreeDS(abandoned.attemptId, callback(abandoned));
    expect(late.status).toBe("FAILED");
    expect(iyzico.threeDSCompletions).toBe(0);
  });

  it("veritabanı aynı siparişe ikinci başarılı ödemeyi kabul etmez", async () => {
    const orderId = newOrderId();
    await payments.start(newKey(), TRACE, paymentRequest(orderId, 10));

    // Durum makinesini atlayıp elle ikinci bir başarılı deneme yazmaya çalışalım.
    const second = await store.create({ orderId, idempotencyKey: newKey(), requestHash: "x".repeat(64), amount: "10.00", threeDS: false, traceId: TRACE });
    await store.move(second.id, "CREATED", { to: "SENT", note: "test" });
    const error = await store.move(second.id, "SENT", { to: "SUCCEEDED", note: "test" }).catch((e: unknown) => e);
    expect(isUniqueViolation(error)).toBe(true);
  });

  it("iade yalnızca bir kez yapılır", async () => {
    const paid = (await payments.start(newKey(), TRACE, paymentRequest(newOrderId(), 40))).view;

    expect((await payments.refund(paid.attemptId, "127.0.0.1")).status).toBe("REFUNDED");
    await expect(payments.refund(paid.attemptId, "127.0.0.1")).rejects.toMatchObject({ type: "not-refundable" });
    expect(iyzico.refunds).toBe(1);
  });

  it("her geçiş geçmiş tablosuna yazılır", async () => {
    const paid = (await payments.start(newKey(), TRACE, paymentRequest(newOrderId(), 15))).view;

    const events = await store.events(paid.attemptId);
    expect(events.map((e) => `${e.fromStatus ?? "-"}→${e.toStatus}`)).toEqual(["-→CREATED", "CREATED→SENT", "SENT→SUCCEEDED"]);
  });
});

function callback(started: PaymentAttemptView): ThreeDSCallback {
  return { status: "success", paymentId: started.iyzicoPaymentId ?? undefined, conversationData: "data", conversationId: started.attemptId, mdStatus: "1", signature: "imza" };
}

/** Aynı işi n kez aynı anda başlatır; dönen değer veya fırlayan hata listesini verir. */
async function concurrently<T>(times: number, work: () => Promise<T>): Promise<(T | Error)[]> {
  return Promise.all(Array.from({ length: times }, () => work().catch((e: unknown) => e as Error)));
}
