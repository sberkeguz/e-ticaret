import { describe, expect, it } from "vitest";
import { money, moneyText, POSITIVE_MONEY, sumMoney } from "../src/money.js";
import { formatPrice } from "../src/provider/iyzico-api.gateway.js";

/** Para float ile hesaplanmaz; tutarlar metin olarak taşınır. */
describe("para", () => {
  it("float'ın bozduğu toplamı doğru hesaplar", () => {
    expect(0.1 + 0.2).not.toBe(0.3); // float: 0.30000000000000004
    expect(sumMoney(["0.10", "0.20"]).equals(money("0.30"))).toBe(true);
    expect(sumMoney(["12345678.90", "0.01"]).equals(money("12345678.91"))).toBe(true);
  });

  it("geçerli ve geçersiz tutarları ayırır", () => {
    for (const ok of ["5", "5.0", "12.50", "0.01", "9999999999.99"]) expect(POSITIVE_MONEY.test(ok)).toBe(true);
    for (const bad of ["0", "0.00", "-5", "5.123", "abc", "", "1e3"]) expect(POSITIVE_MONEY.test(bad)).toBe(false);
  });

  it("biçimler", () => {
    expect(moneyText("5")).toBe("5.00");
    expect(formatPrice("12.50")).toBe("12.5");
    expect(formatPrice("5.00")).toBe("5.0");
  });
});
