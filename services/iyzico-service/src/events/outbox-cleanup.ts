import { Inject, Injectable, Logger, type OnApplicationBootstrap, type OnModuleDestroy } from "@nestjs/common";
import { APP_CONFIG, type AppConfig } from "../config.js";
import { PrismaService } from "../prisma.service.js";

/**
 * Outbox tablosu sonsuza kadar büyümesin: RabbitMQ'ya gönderilmiş ve üzerinden
 * outboxRetentionDays gün geçmiş satırlar silinir. Gönderilmemiş satırlara hiç dokunulmaz.
 * (Ödeme denemeleri ve geçmişi silinmez; onlar ödeme kaydıdır.)
 */
@Injectable()
export class OutboxCleanup implements OnApplicationBootstrap, OnModuleDestroy {
  private readonly log = new Logger(OutboxCleanup.name);
  private timer: NodeJS.Timeout | undefined;

  constructor(
    private readonly db: PrismaService,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
  ) {}

  onApplicationBootstrap(): void {
    if (this.config.cleanupIntervalMs > 0) {
      this.timer = setInterval(() => void this.runOnce().catch((e: Error) => this.log.warn(e.message)), this.config.cleanupIntervalMs);
    }
  }

  onModuleDestroy(): void {
    clearInterval(this.timer);
  }

  async runOnce(now: Date = new Date()): Promise<number> {
    const before = new Date(now.getTime() - this.config.outboxRetentionDays * 24 * 60 * 60_000);
    const { count } = await this.db.outboxMessage.deleteMany({ where: { publishedAt: { lt: before } } });
    if (count > 0) {
      this.log.log(`${count} eski outbox satırı silindi (${this.config.outboxRetentionDays} günden eski, gönderilmiş)`);
    }
    return count;
  }
}
