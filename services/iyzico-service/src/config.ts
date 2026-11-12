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
  /** Mutabakat işi bu aralıkla çalışır; 0 = kapalı. */
  reconcileIntervalMs: number;
  iyzico: {
    apiKey: string;
    secretKey: string;
    baseUrl: string;
    /** 3D dönüşü tarayıcıdan backend'e gelir (ngrok), backend bu servise iletir. */
    callbackUrl: string;
    timeoutMs: number;
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
    reconcileIntervalMs: Number(env.RECONCILE_INTERVAL_MS ?? 60_000),
    iyzico: {
      apiKey: env.IYZIPAY_API_KEY ?? "",
      secretKey: env.IYZIPAY_SECRET_KEY ?? "",
      baseUrl: env.IYZIPAY_BASE_URL ?? "https://sandbox-api.iyzipay.com",
      callbackUrl: env.IYZIPAY_CALLBACK_URL ?? "http://localhost:8080/api/payments/callback",
      timeoutMs: Number(env.IYZIPAY_TIMEOUT_MS ?? 60_000),
    },
  };
}
