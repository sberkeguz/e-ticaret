import { Inject, Injectable, Logger, type OnApplicationBootstrap, type OnModuleDestroy } from "@nestjs/common";
import { type ChannelModel, type ConfirmChannel, connect } from "amqplib";
import { APP_CONFIG, type AppConfig } from "../config.js";
import { PrismaService } from "../prisma.service.js";
import { EVENT_VERSION, PAYMENTS_EXCHANGE, UNROUTED_EXCHANGE, UNROUTED_QUEUE } from "./payment-events.js";

const BATCH = 50;

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
  }

  async onModuleDestroy(): Promise<void> {
    clearInterval(this.timer);
    await this.disconnect();
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
            channel.publish(row.exchange, row.routing_key, Buffer.from(JSON.stringify(row.payload)), {
              messageId: row.id,
              type: row.type,
              contentType: "application/json",
              persistent: true, // RabbitMQ yeniden başlasa da kaybolmaz
              timestamp: Math.floor(row.created_at.getTime() / 1000),
              headers: { "x-event-version": EVENT_VERSION },
            });
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
