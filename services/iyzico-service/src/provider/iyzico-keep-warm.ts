import { Inject, Injectable, Logger, type OnApplicationBootstrap, type OnModuleDestroy } from "@nestjs/common";
import { request as httpRequest } from "node:http";
import { request as httpsRequest } from "node:https";
import { APP_CONFIG, type AppConfig } from "../config.js";

/**
 * iyzico sandbox, ~10 dakika sessizlikten sonraki ilk bağlantıyı düşürüyor (ölçüldü: ~3 sn bekleme
 * + kopma; sonraki bağlantılar normal). Bu iş, iyzico'ya birkaç dakikada bir çok küçük bir istek
 * (HEAD /) atarak o sessizliğin hiç oluşmamasını sağlar. Ödeme koduna dokunmaz; cezayı ödeme
 * yerine bu istek yer. Ödeme API'sine istek değildir, para veya kayıtla ilgisi yoktur.
 */
@Injectable()
export class IyzicoKeepWarm implements OnApplicationBootstrap, OnModuleDestroy {
  private readonly log = new Logger(IyzicoKeepWarm.name);
  private timer: NodeJS.Timeout | undefined;

  constructor(@Inject(APP_CONFIG) private readonly config: AppConfig) {}

  onApplicationBootstrap(): void {
    if (this.config.iyzico.keepWarmIntervalMs > 0) {
      void this.ping();
      this.timer = setInterval(() => void this.ping(), this.config.iyzico.keepWarmIntervalMs);
    }
  }

  onModuleDestroy(): void {
    clearInterval(this.timer);
  }

  /** Süreyi ms olarak döner; bağlantı kurulamazsa -1. */
  ping(): Promise<number> {
    const started = performance.now();
    const url = new URL("/", this.config.iyzico.baseUrl);
    const request = url.protocol === "http:" ? httpRequest : httpsRequest;
    return new Promise((resolve) => {
      const req = request(url, { method: "HEAD", agent: false, timeout: 10_000 }, (res) => {
        res.resume();
        res.on("end", () => {
          const ms = Math.round(performance.now() - started);
          if (ms > 1_000) {
            this.log.log(`iyzico bağlantısı soğumuştu, ısıtıldı (${ms} ms)`);
          }
          resolve(ms);
        });
      });
      req.on("timeout", () => req.destroy(new Error("zaman aşımı")));
      req.on("error", (error) => {
        this.log.debug(`iyzico ısıtma isteği başarısız (${Math.round(performance.now() - started)} ms): ${error.message}`);
        resolve(-1);
      });
      req.end();
    });
  }
}
