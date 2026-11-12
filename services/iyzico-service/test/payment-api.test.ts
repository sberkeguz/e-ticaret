import type { INestApplication } from "@nestjs/common";
import request from "supertest";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { createTestApp, newKey, newOrderId, paymentBody, TOKEN } from "./test-app.js";

/** HTTP sözleşmesi: durum kodları, başlıklar, RFC 9457 hata biçimi. Backend buna göre yazıldı. */
describe("ödeme API'si", () => {
  let app: INestApplication;

  beforeAll(async () => {
    ({ app } = await createTestApp());
  });
  afterAll(() => app.close());

  const post = (path: string) => request(app.getHttpServer()).post(path).set("X-Internal-Token", TOKEN);

  it("ilk istek 201, aynı anahtarla tekrarı 200 + Idempotent-Replayed", async () => {
    const key = newKey();
    const body = paymentBody(newOrderId(), 99.9);

    const first = await post("/payments")
      .set("Idempotency-Key", key)
      .set("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
      .send(body);
    expect(first.status).toBe(201);
    expect(first.headers.location).toBe(`/payments/${first.body.attemptId}`);
    expect(first.body.status).toBe("SUCCEEDED");
    expect(first.body.traceId).toBe("4bf92f3577b34da6a3ce929d0e0e4736");

    const second = await post("/payments").set("Idempotency-Key", key).send(body);
    expect(second.status).toBe(200);
    expect(second.headers["idempotent-replayed"]).toBe("true");
  });

  it("Idempotency-Key yoksa 400 problem+json", async () => {
    const response = await post("/payments").send(paymentBody(newOrderId(), 10));
    expect(response.status).toBe(400);
    expect(response.headers["content-type"]).toContain("application/problem+json");
    expect(response.body.type).toBe("/problems/validation-error");
  });

  it("geçersiz gövde 400 problem+json", async () => {
    const response = await post("/payments").set("Idempotency-Key", newKey()).send({ ...paymentBody(newOrderId(), 10), card: { number: "12" } });
    expect(response.status).toBe(400);
    expect(response.body.status).toBe(400);
  });

  it("sepet toplamı tutmazsa 400", async () => {
    const body = { ...paymentBody(newOrderId(), 10), amount: 11 };
    const response = await post("/payments").set("Idempotency-Key", newKey()).send(body);
    expect(response.status).toBe(400);
    expect(response.body.type).toBe("/problems/basket-total-mismatch");
  });

  it("anahtar başka içerikle kullanılırsa 422", async () => {
    const key = newKey();
    const orderId = newOrderId();
    await post("/payments").set("Idempotency-Key", key).send(paymentBody(orderId, 10));

    const response = await post("/payments").set("Idempotency-Key", key).send(paymentBody(orderId, 20));
    expect(response.status).toBe(422);
    expect(response.body.type).toBe("/problems/idempotency-key-reused");
  });

  it("token olmadan istek 401; sağlık kontrolü ve API belgesi açık", async () => {
    const server = app.getHttpServer();
    expect((await request(server).get("/payments/00000000-0000-0000-0000-000000000000")).status).toBe(401);
    expect((await request(server).get("/payments/00000000-0000-0000-0000-000000000000").set("X-Internal-Token", "yanlis")).status).toBe(401);
    expect((await request(server).get("/health")).status).toBe(200);
    expect((await request(server).get("/v3/api-docs")).status).toBe(200);
  });

  it("olmayan deneme 404", async () => {
    const response = await request(app.getHttpServer())
      .get("/payments/00000000-0000-0000-0000-000000000000")
      .set("X-Internal-Token", TOKEN);
    expect(response.status).toBe(404);
    expect(response.body.type).toBe("/problems/attempt-not-found");
  });

  it("3D dönüşü, doğrulaması ve iadesi 200; eski iade 204", async () => {
    const started = await post("/payments").set("Idempotency-Key", newKey()).send(paymentBody(newOrderId(), 30, true));
    expect(started.body.status).toBe("AWAITING_3DS");
    const id = started.body.attemptId;

    const completed = await post(`/payments/${id}/threeds-complete`).send({
      status: "success", paymentId: started.body.iyzicoPaymentId, conversationData: "d", conversationId: id, mdStatus: "1", signature: "s",
    });
    expect(completed.status).toBe(200);
    expect(completed.body.status).toBe("SUCCEEDED");

    expect((await post(`/payments/${id}/verify`)).status).toBe(200);
    const refunded = await post(`/payments/${id}/refund`).send({ clientIp: "127.0.0.1" });
    expect(refunded.status).toBe(200);
    expect(refunded.body.status).toBe("REFUNDED");

    const legacy = await post("/legacy-refunds").send({ iyzicoPaymentId: "123", amount: 5, clientIp: "127.0.0.1", orderId: 41 });
    expect(legacy.status).toBe(204);
  });

  it("kart numarası hiçbir cevapta yer almaz", async () => {
    const response = await post("/payments").set("Idempotency-Key", newKey()).send(paymentBody(newOrderId(), 10));
    expect(JSON.stringify(response.body)).not.toContain("5528790000000008");
  });
});
