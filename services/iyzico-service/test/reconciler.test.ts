import type { INestApplication } from "@nestjs/common";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { AttemptStatus } from "../src/attempt/attempt-status.js";
import { AttemptStore } from "../src/attempt/attempt.store.js";
import type { PaymentEvent } from "../src/events/payment-events.js";
import { Reconciler } from "../src/payment/reconciler.js";
import { PrismaService } from "../src/prisma.service.js";
import type { FakeIyzicoGateway } from "./fake-iyzico.gateway.js";
import { createTestApp, newKey, newOrderId } from "./test-app.js";

const TRACE = "c".repeat(32);

/** Adım 4: takılan denemeler dakikada bir sonuca bağlanır; asla kör tekrar yapılmaz. */
describe("mutabakat işi", () => {
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

  it("iyzico'ya hiç gitmemiş deneme (CREATED) başarısız sayılır", async () => {
    const id = await attemptIn(["CREATED"], { minutesAgo: 10 });

    await reconciler.runOnce();

    expect(await statusOf(id)).toBe("FAILED");
    expect(await eventTypes(id)).toEqual(["PaymentFailed"]);
    expect(iyzico.charges + iyzico.inquiries).toBe(0);
  });

  it("cevabı kaybolan 2D ödeme (SENT) tekrar gönderilmez, iyzico'ya sorulur", async () => {
    const id = await attemptIn(["CREATED", "SENT"], { minutesAgo: 10 });

    await reconciler.runOnce();

    expect(iyzico.charges).toBe(0);
    expect(iyzico.inquiries).toBe(1);
    expect(await statusOf(id)).toBe("SUCCEEDED");
    // Önce "belirsiz" (backend siparişi incelemeye alır), sonra "başarılı" (ödendi yapar).
    expect(await eventTypes(id)).toEqual(["PaymentVerifying", "PaymentSucceeded"]);
  });

  it("SMS sonrası cevabı kaybolan 3D ödeme (CONFIRM_SENT) de sorulur", async () => {
    const id = await attemptIn(["CREATED", "SENT", "AWAITING_3DS", "CONFIRM_SENT"], { minutesAgo: 10, threeDS: true });

    await reconciler.runOnce();

    expect(iyzico.threeDSCompletions).toBe(0);
    expect(await statusOf(id)).toBe("SUCCEEDED");
  });

  it("cevabı kaybolan 3D başlatma (SENT) başarısız sayılır; para çekmediği için sorulmaz", async () => {
    const id = await attemptIn(["CREATED", "SENT"], { minutesAgo: 10, threeDS: true });

    await reconciler.runOnce();

    expect(await statusOf(id)).toBe("FAILED");
    expect(iyzico.inquiries).toBe(0);
  });

  it("bırakılan SMS ekranı 15 dk sonra kapanır, daha yenisine dokunulmaz", async () => {
    const old = await attemptIn(["CREATED", "SENT", "AWAITING_3DS"], { minutesAgo: 20, threeDS: true });
    const fresh = await attemptIn(["CREATED", "SENT", "AWAITING_3DS"], { minutesAgo: 5, threeDS: true });

    await reconciler.runOnce();

    expect(await statusOf(old)).toBe("FAILED");
    expect(await statusOf(fresh)).toBe("AWAITING_3DS");
  });

  it("henüz takılmamış denemelere dokunulmaz", async () => {
    const id = await attemptIn(["CREATED", "SENT"], { minutesAgo: 1 });

    await reconciler.runOnce();

    expect(await statusOf(id)).toBe("SENT");
    expect(iyzico.inquiries).toBe(0);
  });

  it("belirsiz ödeme, iyzico netleşene kadar VERIFYING kalır, netleşince çözülür", async () => {
    const id = await attemptIn(["CREATED", "SENT", "VERIFYING"], { minutesAgo: 5 });

    iyzico.inquiryResult = "UNCERTAIN";
    await reconciler.runOnce();
    expect(await statusOf(id)).toBe("VERIFYING");

    await age(id, 5);
    iyzico.inquiryResult = "DECLINED";
    await reconciler.runOnce();
    expect(await statusOf(id)).toBe("FAILED");
    expect(iyzico.charges).toBe(0);
  });

  /** Denemeyi verilen durumlardan geçirip son durumda bırakır ve "dakikalar önce" yapılmış gibi eskitir. */
  async function attemptIn(path: AttemptStatus[], options: { minutesAgo: number; threeDS?: boolean }): Promise<string> {
    const attempt = await store.create({
      orderId: newOrderId(), idempotencyKey: newKey(), requestHash: "r".repeat(64),
      amount: "10.00", threeDS: options.threeDS ?? false, traceId: TRACE,
    });
    for (let i = 1; i < path.length; i++) {
      await store.move(attempt.id, path[i - 1], {
        to: path[i], note: "test", iyzicoPaymentId: path[i] === "AWAITING_3DS" ? `3ds-${attempt.id}` : undefined,
      });
    }
    await age(attempt.id, options.minutesAgo);
    return attempt.id;
  }

  async function age(id: string, minutesAgo: number): Promise<void> {
    await db.$executeRaw`UPDATE "iyzico"."payment_attempts" SET updated_at = now() - make_interval(mins => ${minutesAgo}) WHERE id = ${id}::uuid`;
  }

  async function statusOf(id: string) {
    return (await store.get(id)).status;
  }

  async function eventTypes(id: string): Promise<string[]> {
    const rows = await db.outboxMessage.findMany({ orderBy: { createdAt: "asc" } });
    return rows.filter((row) => (row.payload as unknown as PaymentEvent).data.attemptId === id).map((row) => row.type);
  }
});
