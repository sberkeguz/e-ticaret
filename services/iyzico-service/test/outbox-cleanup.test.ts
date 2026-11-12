import type { INestApplication } from "@nestjs/common";
import { randomUUID } from "node:crypto";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { OutboxCleanup } from "../src/events/outbox-cleanup.js";
import { PrismaService } from "../src/prisma.service.js";
import { createTestApp } from "./test-app.js";

/** Adım 7: gönderilmiş eski outbox satırları silinir; gönderilmemişlere dokunulmaz. */
describe("outbox temizliği", () => {
  let app: INestApplication;
  let db: PrismaService;

  beforeAll(async () => {
    ({ app } = await createTestApp());
    db = app.get(PrismaService);
  });
  afterAll(() => app.close());

  it("7 günden eski gönderilmişleri siler, yenileri ve gönderilmemişleri bırakır", async () => {
    const daysAgo = (days: number) => new Date(Date.now() - days * 24 * 60 * 60_000);
    const row = (publishedAt: Date | null, createdAt: Date) => ({
      id: randomUUID(), exchange: "payments", routingKey: "payment.test", type: "Test", payload: {}, createdAt, publishedAt,
    });
    const oldSent = row(daysAgo(8), daysAgo(8));
    const recentSent = row(daysAgo(1), daysAgo(1));
    const oldUnsent = row(null, daysAgo(30));
    await db.outboxMessage.createMany({ data: [oldSent, recentSent, oldUnsent] });

    await app.get(OutboxCleanup).runOnce();

    const left = (await db.outboxMessage.findMany({ where: { id: { in: [oldSent.id, recentSent.id, oldUnsent.id] } } })).map((r) => r.id);
    expect(left).not.toContain(oldSent.id);
    expect(left).toContain(recentSent.id);
    expect(left).toContain(oldUnsent.id);
    await db.outboxMessage.deleteMany({ where: { id: { in: left } } });
  });
});
