import { randomBytes } from "node:crypto";

const TRACEPARENT = /^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$/;

/**
 * İzleme numarası: W3C Trace Context biçiminde 32 hex karakter.
 * İstekte traceparent başlığı varsa onun trace-id'si kullanılır, yoksa yenisi üretilir.
 * OpenTelemetry eklendiğinde aynı numara uçtan uca taşınır.
 */
export function traceIdFrom(traceparent: string | undefined): string {
  const match = traceparent?.trim().match(TRACEPARENT);
  if (match && match[1] !== "0".repeat(32)) {
    return match[1];
  }
  return randomBytes(16).toString("hex");
}
