import { Injectable } from "@nestjs/common";
import { randomUUID } from "node:crypto";
import { Prisma, type PaymentAttempt } from "../generated/prisma/client.js";
import { outboxRowFor } from "../events/payment-events.js";
import { PrismaService } from "../prisma.service.js";
import { AttemptStatus, canMoveTo, NOT_CHARGED_YET } from "./attempt-status.js";

export type { PaymentAttempt };
type Tx = Prisma.TransactionClient;

/** Bir geçişin hedefi ve onunla birlikte yazılacak alanlar. */
export interface Transition {
  to: AttemptStatus;
  note: string;
  iyzicoPaymentId?: string | null;
  failureReason?: string | null;
}

export interface NewAttempt {
  orderId: number;
  idempotencyKey: string;
  requestHash: string;
  amount: string;
  threeDS: boolean;
  traceId: string;
}

export class AttemptNotFoundError extends Error {
  constructor(id: string) {
    super(`Ödeme denemesi bulunamadı: ${id}`);
  }
}

/**
 * Denemelerin veritabanı tarafı. Her public metot kendi kısa işlemidir (transaction):
 * iyzico çağrısı hiçbir zaman açık bir işlemin içinde yapılmaz (iade hariç, bkz. withLock).
 */
@Injectable()
export class AttemptStore {
  constructor(private readonly db: PrismaService) {}

  /**
   * Yeni deneme açar. Aynı siparişin henüz para çekme isteği gitmemiş eski denemesi
   * (CREATED veya AWAITING_3DS) varsa önce onu FAILED yapar; böylece kullanıcı 3D ekranını
   * kapatıp tekrar deneyebilir ve eski denemenin geç gelen dönüşü para çekemez.
   *
   * Tekil anahtar veya "siparişte tek aktif deneme" kuralı çiğnenirse Prisma P2002 hatası
   * fırlar ve bu işlem tümüyle geri alınır.
   */
  async create(input: NewAttempt): Promise<PaymentAttempt> {
    return this.db.$transaction(async (tx) => {
      for (const old of await lockByOrder(tx, input.orderId, NOT_CHARGED_YET)) {
        await record(tx, old, {
          to: "FAILED",
          note: "Aynı sipariş için yeni deneme başladı",
          failureReason: "Yerine yeni bir deneme başladı.",
        });
      }
      const now = new Date();
      const attempt = await tx.paymentAttempt.create({
        data: {
          id: randomUUID(),
          orderId: BigInt(input.orderId),
          idempotencyKey: input.idempotencyKey,
          requestHash: input.requestHash,
          amount: new Prisma.Decimal(input.amount),
          currency: "TRY",
          threeDS: input.threeDS,
          status: "CREATED",
          traceId: input.traceId,
          createdAt: now,
          updatedAt: now,
        },
      });
      await tx.paymentAttemptEvent.create({
        data: { attemptId: attempt.id, fromStatus: null, toStatus: "CREATED", note: "Deneme oluşturuldu", occurredAt: now },
      });
      return attempt;
    });
  }

  /**
   * Satır kilidiyle geçiş yapar, ama yalnızca deneme hâlâ `from` durumundaysa.
   * Başka bir istek önce davrandıysa null döner ve hiçbir şey değişmez.
   */
  async move(id: string, from: AttemptStatus, transition: Transition): Promise<PaymentAttempt | null> {
    return this.db.$transaction(async (tx) => {
      const attempt = await lockById(tx, id);
      if (attempt.status !== from) {
        return null;
      }
      return record(tx, attempt, transition);
    });
  }

  /**
   * Satırı kilitler ve kilit açıkken işi yapar. Yalnızca iade için: iyzico çağrısı kilit
   * içinde yapılır ki aynı ödeme iki kez iade edilemesin.
   */
  async withLock<T>(id: string, work: (attempt: PaymentAttempt, tx: Tx) => Promise<T>): Promise<T> {
    return this.db.$transaction(async (tx) => work(await lockById(tx, id), tx), { timeout: 120_000 });
  }

  async get(id: string): Promise<PaymentAttempt> {
    const attempt = await this.db.paymentAttempt.findUnique({ where: { id } });
    if (!attempt) {
      throw new AttemptNotFoundError(id);
    }
    return attempt;
  }

  async findByIdempotencyKey(idempotencyKey: string): Promise<PaymentAttempt | null> {
    return this.db.paymentAttempt.findUnique({ where: { idempotencyKey } });
  }

  /** Mutabakat işi için: belli bir süredir aynı durumda bekleyen denemeler. */
  async findStuck(olderThan: Partial<Record<AttemptStatus, Date>>, limit = 50): Promise<PaymentAttempt[]> {
    const conditions = Object.entries(olderThan).map(([status, before]) => ({
      status: status as AttemptStatus,
      updatedAt: { lt: before },
    }));
    if (conditions.length === 0) {
      return [];
    }
    return this.db.paymentAttempt.findMany({ where: { OR: conditions }, orderBy: { updatedAt: "asc" }, take: limit });
  }

  async events(id: string) {
    return this.db.paymentAttemptEvent.findMany({ where: { attemptId: id }, orderBy: { id: "asc" } });
  }
}

/** Durumu değiştirir ve geçmişe bir satır yazar. Açık bir işlemin içinde çalışır. */
export async function record(tx: Tx, attempt: PaymentAttempt, transition: Transition): Promise<PaymentAttempt> {
  if (!canMoveTo(attempt.status, transition.to)) {
    throw new Error(`Geçersiz geçiş: ${attempt.status} → ${transition.to} (deneme ${attempt.id})`);
  }
  const now = new Date();
  const updated = await tx.paymentAttempt.update({
    where: { id: attempt.id },
    data: {
      status: transition.to,
      updatedAt: now,
      ...(transition.iyzicoPaymentId != null && { iyzicoPaymentId: transition.iyzicoPaymentId }),
      ...(transition.failureReason != null && { failureReason: limit(transition.failureReason) }),
    },
  });
  await tx.paymentAttemptEvent.create({
    data: {
      attemptId: attempt.id,
      fromStatus: attempt.status,
      toStatus: transition.to,
      note: limit(transition.note),
      occurredAt: now,
    },
  });
  // Outbox: olay, durum değişikliğiyle AYNI işlemde yazılır. İşlem geri alınırsa olay da
  // yazılmamış olur; işlem tamamlanırsa olay kaybolmaz (relay mutlaka gönderir).
  const outboxRow = outboxRowFor(updated);
  if (outboxRow) {
    await tx.outboxMessage.create({ data: outboxRow });
  }
  return updated;
}

/** SELECT ... FOR UPDATE: aynı denemeyi aynı anda iki istek değiştiremez. */
async function lockById(tx: Tx, id: string): Promise<PaymentAttempt> {
  const rows = await tx.$queryRaw<{ id: string }[]>`
      SELECT id FROM "iyzico"."payment_attempts" WHERE id = ${id}::uuid FOR UPDATE`;
  if (rows.length === 0) {
    throw new AttemptNotFoundError(id);
  }
  return tx.paymentAttempt.findUniqueOrThrow({ where: { id } });
}

async function lockByOrder(tx: Tx, orderId: number, statuses: readonly AttemptStatus[]): Promise<PaymentAttempt[]> {
  const rows = await tx.$queryRaw<{ id: string }[]>`
      SELECT id FROM "iyzico"."payment_attempts"
      WHERE order_id = ${orderId} AND status::text = ANY(${[...statuses]}) FOR UPDATE`;
  if (rows.length === 0) {
    return [];
  }
  return tx.paymentAttempt.findMany({ where: { id: { in: rows.map((row) => row.id) } } });
}

/** Tekil kısıt ihlali mi? (Postgres 23505 → Prisma P2002) */
export function isUniqueViolation(error: unknown): boolean {
  if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2002") {
    return true;
  }
  return String((error as { message?: string })?.message ?? "").includes("23505");
}

function limit(text: string): string {
  return text.length <= 500 ? text : text.slice(0, 500);
}
