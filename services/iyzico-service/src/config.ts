/** Servisin bütün ayarları ortam değişkenlerinden, tek yerde okunur. */
export interface AppConfig {
  port: number;
  databaseUrl: string;
  /** Backend bu değeri X-Internal-Token başlığında gönderir. Boşsa hiçbir istek geçmez. */
  internalApiToken: string;
  /** amqp://kullanıcı:şifre@host:5672 — boşsa olaylar outbox'ta bekler, gönderilmez. */
  rabbitmqUrl: string;
  /** Relay outbox'a bu aralıkla bakar. */
  outboxIntervalMs: number;
  /** Yeni olay yazılınca veritabanının haberini (LISTEN/NOTIFY) dinleyip hemen gönder. */
  outboxListen: boolean;
  /** Mutabakat işi bu aralıkla çalışır; 0 = kapalı. */
  reconcileIntervalMs: number;
  /** Temizlik işi bu aralıkla çalışır; 0 = kapalı. */
  cleanupIntervalMs: number;
  /** Gönderilmiş outbox satırları bu kadar gün saklanır. */
  outboxRetentionDays: number;
  iyzico: {
    apiKey: string;
    secretKey: string;
    baseUrl: string;
    /** 3D dönüşü tarayıcıdan backend'e gelir (ngrok), backend bu servise iletir. */
    callbackUrl: string;
    timeoutMs: number;
    /** Bağlantı + TLS el sıkışması için süre; dolarsa hemen tekrar denenir (istek henüz gitmemiştir). */
    handshakeTimeoutMs: number;
    /** iyzico bağlantısını sıcak tutmak için küçük istek aralığı; 0 = kapalı. */
    keepWarmIntervalMs: number;
  };
}

export const APP_CONFIG = Symbol("APP_CONFIG");

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  return {
    port: Number(env.PORT ?? 8081),
    databaseUrl: env.DATABASE_URL ?? "postgresql://localhost:5432/eticaret?schema=iyzico",
    internalApiToken: env.INTERNAL_API_TOKEN ?? "",
    rabbitmqUrl: env.RABBITMQ_URL ?? "",
    outboxIntervalMs: Number(env.OUTBOX_INTERVAL_MS ?? 1_000),
    outboxListen: env.OUTBOX_LISTEN !== "false",
    reconcileIntervalMs: Number(env.RECONCILE_INTERVAL_MS ?? 60_000),
    cleanupIntervalMs: Number(env.CLEANUP_INTERVAL_MS ?? 60 * 60_000),
    outboxRetentionDays: Number(env.OUTBOX_RETENTION_DAYS ?? 7),
    iyzico: {
      apiKey: env.IYZIPAY_API_KEY ?? "",
      secretKey: env.IYZIPAY_SECRET_KEY ?? "",
      baseUrl: env.IYZIPAY_BASE_URL ?? "https://sandbox-api.iyzipay.com",
      callbackUrl: env.IYZIPAY_CALLBACK_URL ?? "http://localhost:8080/api/payments/callback",
      timeoutMs: Number(env.IYZIPAY_TIMEOUT_MS ?? 60_000),
      // 1 sn denendi: sandbox uzun aradan sonra birkaç saniye hiç bağlantı kabul etmediği için
      // 3 deneme de düştü ve ödeme başarısız oldu. Kopmalar ~3,5 sn'de kendiliğinden geliyor;
      // bu süre yalnızca hiç cevap gelmeyen bağlantının sonsuza dek beklemesini önler.
      handshakeTimeoutMs: Number(env.IYZIPAY_HANDSHAKE_TIMEOUT_MS ?? 10_000),
      // iyzico birkaç dk sessizlikten sonra ilk bağlantıyı düşürüyor (4 dk bile yetiyor); dakikada bir ısıtılır.
      keepWarmIntervalMs: Number(env.IYZIPAY_KEEP_WARM_MS ?? 60_000),
    },
  };
}
