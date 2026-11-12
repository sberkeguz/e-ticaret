import { createHmac } from "node:crypto";
import { describe, expect, it } from "vitest";
import { loadConfig } from "../src/config.js";
import { formatPrice, IyzicoApiGateway } from "../src/provider/iyzico-api.gateway.js";

const SECRET = "gizli";
const gateway = new IyzicoApiGateway({ ...loadConfig({}), iyzico: { ...loadConfig({}).iyzico, apiKey: "k", secretKey: SECRET } });
const hmac = (text: string) => createHmac("sha256", SECRET).update(text).digest("hex");

/** iyzico'ya özgü küçük kurallar: fiyat biçimi ve imzalar. */
describe("iyzico kuralları", () => {
  it("fiyatı resmî SDK gibi biçimler", () => {
    expect(formatPrice(5)).toBe("5.0");
    expect(formatPrice("12.50")).toBe("12.5");
    expect(formatPrice(99.9)).toBe("99.9");
  });

  it("3D dönüş imzasını doğrular", () => {
    const callback = { status: "success", paymentId: "123", conversationData: "abc", conversationId: "conv", mdStatus: "1" };
    const signature = hmac("abc:conv:1:123:success");

    expect(gateway.isCallbackSignatureValid({ ...callback, signature })).toBe(true);
    expect(gateway.isCallbackSignatureValid({ ...callback, signature: signature.toUpperCase() })).toBe(true);
    expect(gateway.isCallbackSignatureValid({ ...callback, mdStatus: "0", signature })).toBe(false);
    expect(gateway.isCallbackSignatureValid(callback)).toBe(false);
  });
});
