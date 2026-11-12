import { Inject, Injectable, Logger, type OnApplicationBootstrap, type OnModuleDestroy } from "@nestjs/common";
import type { AttemptStatus } from "../attempt/attempt-status.js";
import { AttemptStore } from "../attempt/attempt.store.js";
import { APP_CONFIG, type AppConfig } from "../config.js";
import { PaymentAttemptService } from "./payment-attempt.service.js";

/**
 * Bir deneme bu durumda bu kadar süre kaldıysa takılmış sayılır.
 * iyzico çağrısının zaman aşımı 60 sn; 3 dk dolduysa o çağrı artık sürmüyordur.
 */
export const STUCK_AFTER_MS: Record<"CREATED" | "SENT" | "CONFIRM_SENT" | "AWAITING_3DS" | "VERIFYING", number> = {
  CREATED: 3 * 60_000,
  SENT: 3 * 60_000,
  CONFIRM_SENT: 3 * 60_000,
  AWAITING_3DS: 15 * 60_000,
  /** VERIFYING her turda iyzico'ya sorulur; yeni girmiş olanı bir dakika bekletiriz. */
  VERIFYING: 60_000,
};

export interface ReconcileSummary {
  checked: number;
  resolved: number;
}

/**
 * Mutabakat işi: dakikada bir, ara durumda takılan denemeleri sonuca bağlar.
 *  - CREATED (istek hiç gitmedi)          → FAILED
 *  - SENT/CONFIRM_SENT (cevap kayboldu)   → VERIFYING → iyzico'ya sor
 *  - AWAITING_3DS (SMS ekranı bırakıldı)  → FAILED
 *  - VERIFYING (sonuç belirsiz)           → iyzico'ya sor
 * Sonuçlar olay olarak yayınlanır; fazladan alınan ödemeyi backend iade eder.
 */
@Injectable()
export class Reconciler implements OnApplicationBootstrap, OnModuleDestroy {
  private readonly log = new Logger(Reconciler.name);
  private timer: NodeJS.Timeout | undefined;
  private running = false;

  constructor(
    private readonly store: AttemptStore,
    private readonly payments: PaymentAttemptService,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
  ) {}

  onApplicationBootstrap(): void {
    if (this.config.reconcileIntervalMs > 0) {
      this.timer = setInterval(() => void this.runOnce(), this.config.reconcileIntervalMs);
    }
  }

  onModuleDestroy(): void {
    clearInterval(this.timer);
  }

  async runOnce(now: Date = new Date()): Promise<ReconcileSummary> {
    if (this.running) {
      return { checked: 0, resolved: 0 };
    }
    this.running = true;
    try {
      const olderThan = Object.fromEntries(
        Object.entries(STUCK_AFTER_MS).map(([status, ms]) => [status, new Date(now.getTime() - ms)]),
      ) as Partial<Record<AttemptStatus, Date>>;
      const stuck = await this.store.findStuck(olderThan);
      let resolved = 0;
      for (const attempt of stuck) {
        try {
          const after = await this.payments.recoverStuck(attempt);
          if (after.status !== attempt.status) {
            resolved++;
            this.log.log(`Mutabakat: deneme ${attempt.id} (sipariş ${attempt.orderId}) ${attempt.status} → ${after.status}`);
          }
        } catch (error) {
          this.log.warn(`Mutabakat: deneme ${attempt.id} çözülemedi: ${(error as Error).message}`);
        }
      }
      return { checked: stuck.length, resolved };
    } finally {
      this.running = false;
    }
  }
}
