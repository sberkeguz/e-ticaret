import { Injectable, Logger, type OnModuleDestroy } from "@nestjs/common";

/** Kapanırken yarım kalan işleri en fazla bu kadar bekleriz (iyzico zaman aşımından biraz fazla). */
const SHUTDOWN_WAIT_MS = 90_000;

/**
 * İsteğe cevap verdikten sonra arka planda süren işler (asenkron 2D ödeme).
 * Servis kapanırken yarım kalan ödemelerin bitmesini bekler; böylece "istek iyzico'ya gitti
 * ama sonucu yazılmadı" durumu normal bir yeniden başlatmada oluşmaz.
 */
@Injectable()
export class BackgroundWork implements OnModuleDestroy {
  private readonly log = new Logger(BackgroundWork.name);
  private readonly running = new Set<Promise<unknown>>();

  run(label: string, work: () => Promise<unknown>): void {
    const task = work()
      .catch((error: unknown) => this.log.error(`Arka plan işi başarısız: ${label}`, (error as Error)?.stack))
      .finally(() => this.running.delete(task));
    this.running.add(task);
  }

  /** Testler ve kapanış için: o an süren bütün işler bitene kadar bekler. */
  async idle(): Promise<void> {
    while (this.running.size > 0) {
      await Promise.allSettled([...this.running]);
    }
  }

  async onModuleDestroy(): Promise<void> {
    if (this.running.size === 0) {
      return;
    }
    this.log.log(`Kapanmadan önce ${this.running.size} ödemenin bitmesi bekleniyor`);
    await Promise.race([this.idle(), new Promise((resolve) => setTimeout(resolve, SHUTDOWN_WAIT_MS))]);
  }
}
