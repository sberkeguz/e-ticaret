import { randomBytes } from "node:crypto";
import { context, propagation, ROOT_CONTEXT, trace, TraceFlags, type Context } from "@opentelemetry/api";
import { Inject, Injectable, Logger, type OnApplicationBootstrap, type OnModuleDestroy } from "@nestjs/common";
import { type ChannelModel, type ConfirmChannel, connect } from "amqplib";
import pg from "pg";
import { APP_CONFIG, type AppConfig } from "../config.js";
import { PrismaService } from "../prisma.service.js";
import { EVENT_VERSION, PAYMENTS_EXCHANGE, UNROUTED_EXCHANGE, UNROUTED_QUEUE } from "./payment-events.js";

const BATCH = 50;
/** Veritabanının "yeni olay var" kanalı (bkz. migration 20261113000000_outbox_notify). */
export const OUTBOX_CHANNEL = "iyzico_outbox";
const LISTEN_RETRY_MS = 5_000;

interface OutboxRow {
  id: string;
  exchange: string;
  routing_key: string;
  type: string;
  payload: unknown;
  created_at: Date;
}

/**
 * Outbox'taki olayları RabbitMQ'ya iletir. Her turda:
 *  1. Gönderilmemiş satırları kilitler (FOR UPDATE SKIP LOCKED: birden çok kopya çalışsa da çakışmaz),
 *  2. Yayınlar ve RabbitMQ'nun "aldım" onayını (publisher confirm) bekler,
 *  3. Onay gelince satırları gönderildi diye işaretler.
 *
 * Tur iki şekilde başlar: veritabanı yeni olay yazıldığını haber verince (LISTEN/NOTIFY, hemen) ve
 * yedek olarak saniyede bir. Haber kaçsa ya da dinleme bağlantısı kopsa da olay en geç 1 sn'de gider.
 *
 * RabbitMQ kapalıysa satırlar bekler, bağlantı gelince gönderilir; hiçbir olay kaybolmaz.
 * 2. ile 3. arasında süreç ölürse aynı olay bir kez daha gönderilebilir (en az bir kez teslim):
 * dinleyiciler bu yüzden messageId ile tekrar gelen mesajı ayıklar.
 */
@Injectable()
export class OutboxRelay implements OnApplicationBootstrap, OnModuleDestroy {
  private readonly log = new Logger(OutboxRelay.name);
  private timer: NodeJS.Timeout | undefined;
  private connection: ChannelModel | undefined;
  private channel: ConfirmChannel | undefined;
  private running = false;
  /** Tur sürerken yeni haber geldiyse, tur bitince bir tur daha atılır (yeni satır kaçmasın). */
  private wakeRequested = false;
  private listener: pg.Client | undefined;
  private listenRetry: NodeJS.Timeout | undefined;
  private stopped = false;

  constructor(
    private readonly db: PrismaService,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
  ) {}

  onApplicationBootstrap(): void {
    if (!this.config.rabbitmqUrl) {
      this.log.warn("RABBITMQ_URL tanımlı değil: olaylar outbox'ta bekleyecek, gönderilmeyecek.");
      return;
    }
    this.timer = setInterval(() => void this.publishPending(), this.config.outboxIntervalMs);
    if (this.config.outboxListen) {
      void this.listen();
    }
  }

  async onModuleDestroy(): Promise<void> {
    this.stopped = true;
    clearInterval(this.timer);
    clearTimeout(this.listenRetry);
    await this.listener?.end().catch(() => undefined);
    await this.disconnect();
  }

  /** Hemen bir tur atar; tur zaten sürüyorsa bitince bir tur daha atılır. */
  wake(): void {
    if (this.running) {
      this.wakeRequested = true;
      return;
    }
    void this.publishPending();
  }

  /** Veritabanının "yeni olay var" haberini dinler; bağlantı koparsa 5 sn sonra yeniden bağlanır. */
  private async listen(): Promise<void> {
    const client = new pg.Client({ connectionString: this.config.databaseUrl });
    const retry = (reason: string) => {
      if (this.stopped || this.listener !== client) {
        return;
      }
      this.listener = undefined;
      this.log.warn(`Outbox dinleme bağlantısı koptu (${reason}); ${LISTEN_RETRY_MS / 1000} sn sonra yeniden bağlanılacak`);
      void client.end().catch(() => undefined);
      this.listenRetry = setTimeout(() => void this.listen(), LISTEN_RETRY_MS);
    };
    this.listener = client;
    client.on("notification", () => this.wake());
    client.on("error", (error) => retry(error.message));
    client.on("end", () => retry("bağlantı kapandı"));
    try {
      await client.connect();
      await client.query(`LISTEN ${OUTBOX_CHANNEL}`);
      this.log.log("Outbox yeni olayları anında gönderecek (LISTEN/NOTIFY)");
      // Dinleme başlamadan yazılmış olay kalmasın.
      this.wake();
    } catch (error) {
      retry((error as Error).message);
    }
  }

  /** Bekleyen bütün olayları gönderir. Gönderilen satır sayısını döner. */
  async publishPending(): Promise<number> {
    if (this.running) {
      return 0;
    }
    this.running = true;
    let total = 0;
    try {
      const channel = await this.openChannel();
      for (;;) {
        const sent = await this.publishBatch(channel);
        total += sent;
        if (sent < BATCH) {
          break;
        }
      }
    } catch (error) {
      this.log.warn(`Outbox gönderilemedi, sonra tekrar denenecek: ${(error as Error).message}`);
      if (error instanceof PublishFailed) {
        // İşlem geri alındı, kilitler açıldı: hatayı artık satırlara yazabiliriz.
        await this.recordFailure(error.ids, error.cause as Error);
      }
      await this.disconnect();
    } finally {
      this.running = false;
      if (this.wakeRequested) {
        this.wakeRequested = false;
        setImmediate(() => void this.publishPending());
      }
    }
    return total;
  }

  private async publishBatch(channel: ConfirmChannel): Promise<number> {
    return this.db.$transaction(
      async (tx) => {
        const rows = await tx.$queryRaw<OutboxRow[]>`
            SELECT id, exchange, routing_key, type, payload, created_at
            FROM "iyzico"."outbox_messages"
            WHERE published_at IS NULL
            ORDER BY created_at
            LIMIT ${BATCH}
            FOR UPDATE SKIP LOCKED`;
        if (rows.length === 0) {
          return 0;
        }
        try {
          for (const row of rows) {
            // Olay, ödemeyi başlatan isteğin izine (trace) bağlanır: Grafana'da "istek → iyzico →
            // olay → backend dinleyicisi" tek zincir görünür. Olay geç (ör. 1 dk sonra) yayınlansa da.
            const parent = traceContextOf(row.payload);
            const headers: Record<string, string | number> = { "x-event-version": EVENT_VERSION, ...traceHeaders(parent) };
            context.with(parent, () =>
              channel.publish(row.exchange, row.routing_key, Buffer.from(JSON.stringify(row.payload)), {
                messageId: row.id,
                type: row.type,
                contentType: "application/json",
                persistent: true, // RabbitMQ yeniden başlasa da kaybolmaz
                timestamp: Math.floor(row.created_at.getTime() / 1000),
                headers,
              }),
            );
          }
          await channel.waitForConfirms();
        } catch (error) {
          throw new PublishFailed(rows.map((row) => row.id), error as Error);
        }
        await tx.outboxMessage.updateMany({
          where: { id: { in: rows.map((row) => row.id) } },
          data: { publishedAt: new Date(), attempts: { increment: 1 } },
        });
        this.log.log(`${rows.length} olay RabbitMQ'ya gönderildi`);
        return rows.length;
      },
      { timeout: 30_000 },
    );
  }

  private async recordFailure(ids: string[], error: Error): Promise<void> {
    await this.db.outboxMessage
      .updateMany({ where: { id: { in: ids } }, data: { attempts: { increment: 1 }, lastError: error.message.slice(0, 500) } })
      .catch(() => undefined);
  }

  /** Bağlantıyı gerektiğinde açar ve exchange'leri tanımlar (zaten varsa bir şey yapmaz). */
  private async openChannel(): Promise<ConfirmChannel> {
    if (this.channel) {
      return this.channel;
    }
    const connection = await connect(this.config.rabbitmqUrl);
    connection.on("error", (error: Error) => this.log.warn(`RabbitMQ bağlantı hatası: ${error.message}`));
    connection.on("close", () => {
      this.connection = undefined;
      this.channel = undefined;
    });
    const channel = await connection.createConfirmChannel();
    await channel.assertExchange(UNROUTED_EXCHANGE, "fanout", { durable: true });
    await channel.assertQueue(UNROUTED_QUEUE, { durable: true });
    await channel.bindQueue(UNROUTED_QUEUE, UNROUTED_EXCHANGE, "");
    await channel.assertExchange(PAYMENTS_EXCHANGE, "topic", { durable: true, alternateExchange: UNROUTED_EXCHANGE });
    this.connection = connection;
    this.channel = channel;
    this.log.log("RabbitMQ bağlantısı kuruldu");
    return channel;
  }

  private async disconnect(): Promise<void> {
    const connection = this.connection;
    this.connection = undefined;
    this.channel = undefined;
    await connection?.close().catch(() => undefined);
  }
}

/** Yayın başarısız oldu; hangi satırların etkilendiğini taşır. */
class PublishFailed extends Error {
  constructor(
    readonly ids: string[],
    cause: Error,
  ) {
    super(cause.message, { cause });
  }
}

const TRACE_ID = /^[0-9a-f]{32}$/;

/** Olaydaki traceId'den, OpenTelemetry'nin devam ettirebileceği bir üst bağlam üretir. */
export function traceContextOf(payload: unknown): Context {
  const traceId = (payload as { traceId?: unknown } | null)?.traceId;
  if (typeof traceId !== "string" || !TRACE_ID.test(traceId) || /^0+$/.test(traceId)) {
    return ROOT_CONTEXT;
  }
  return trace.setSpanContext(ROOT_CONTEXT, {
    traceId,
    spanId: randomBytes(8).toString("hex"),
    traceFlags: TraceFlags.SAMPLED,
    isRemote: true,
  });
}

/**
 * Mesaja konacak W3C traceparent başlığı. OpenTelemetry açıksa amqplib izlemesi bunu kendi
 * span'iyle günceller; kapalıysa da başlık gider, backend aynı traceId ile log yazar.
 */
export function traceHeaders(parent: Context): Record<string, string> {
  const headers: Record<string, string> = {};
  propagation.inject(parent, headers);
  const span = trace.getSpanContext(parent);
  if (!headers.traceparent && span) {
    headers.traceparent = `00-${span.traceId}-${span.spanId}-01`;
  }
  return headers;
}
