/**
 * OpenTelemetry: uygulamadan ÖNCE yüklenir (node --import ./dist/instrumentation.js dist/main.js).
 * Gelen HTTP istekleri, iyzico'ya giden HTTPS çağrıları, veritabanı sorguları ve RabbitMQ mesajları
 * iz (span) olur; backend'den gelen traceparent başlığıyla aynı ize bağlanır.
 *
 * OTEL_EXPORTER_OTLP_ENDPOINT verilmezse hiçbir şey açılmaz (testler, yerel çalıştırma).
 * Diğer ayarlar standart OTEL_* değişkenlerinden okunur (OTEL_SERVICE_NAME vb.).
 */
import { register } from "node:module";

if (process.env.OTEL_EXPORTER_OTLP_ENDPOINT) {
  // ESM'de modülleri yüklenirken sarabilmek için gerekli kanca.
  register("@opentelemetry/instrumentation/hook.mjs", import.meta.url);

  const { startNodeSdk } = await import("@opentelemetry/sdk-node");
  const { HttpInstrumentation } = await import("@opentelemetry/instrumentation-http");
  const { ExpressInstrumentation } = await import("@opentelemetry/instrumentation-express");
  const { PgInstrumentation } = await import("@opentelemetry/instrumentation-pg");
  const { AmqplibInstrumentation } = await import("@opentelemetry/instrumentation-amqplib");

  const sdk = startNodeSdk({
    resourceAttributes: { "service.name": process.env.OTEL_SERVICE_NAME ?? "iyzico-service" },
    instrumentations: [
      new HttpInstrumentation({
        // Sağlık kontrolü ve ısıtma isteği izleri kalabalık yapmasın.
        ignoreIncomingRequestHook: (request) => request.url === "/health",
        ignoreOutgoingRequestHook: (options) => options.method === "HEAD",
      }),
      new ExpressInstrumentation(),
      new PgInstrumentation(),
      new AmqplibInstrumentation(),
    ],
  });

  // Kapanırken bekleyen izler gönderilsin.
  for (const signal of ["SIGTERM", "SIGINT"] as const) {
    process.once(signal, () => {
      void sdk.shutdown().catch(() => undefined);
    });
  }
}

export {};
