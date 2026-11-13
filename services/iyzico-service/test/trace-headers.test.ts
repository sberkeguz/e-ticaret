import { ROOT_CONTEXT } from "@opentelemetry/api";
import { describe, expect, it } from "vitest";
import { traceContextOf, traceHeaders } from "../src/events/outbox-relay.js";

/** Olayın traceId'si mesaj başlığına W3C traceparent olarak taşınır; bozuk değer izi kirletmez. */
describe("olay iz başlığı", () => {
  it("geçerli traceId → traceparent", () => {
    const headers = traceHeaders(traceContextOf({ traceId: "b".repeat(32) }));
    expect(headers.traceparent).toMatch(/^00-b{32}-[0-9a-f]{16}-01$/);
  });

  it("traceId yok, bozuk veya sıfır → başlık yok", () => {
    for (const payload of [{}, { traceId: "xyz" }, { traceId: "0".repeat(32) }, null]) {
      expect(traceContextOf(payload)).toBe(ROOT_CONTEXT);
      expect(traceHeaders(traceContextOf(payload))).toEqual({});
    }
  });
});
