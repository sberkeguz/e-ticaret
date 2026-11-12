import { createHash } from "node:crypto";
import type { CreatePaymentRequest } from "../api/payment.dto.js";
import { moneyText } from "../money.js";

/**
 * Aynı Idempotency-Key ile gelen isteğin gerçekten aynı istek olup olmadığını anlamak için özet.
 * Kart numarasının yalnızca son 4 hanesi girer; tam numara hiçbir zaman özetlenmez.
 */
export function requestFingerprint(request: CreatePaymentRequest): string {
  const items = request.items.map((item) => `${item.id}=${plain(item.price)}`).join(",");
  const canonical = [
    String(request.orderId),
    plain(request.amount),
    String(request.threeDS),
    request.card.number.slice(-4),
    items,
  ].join("|");
  return createHash("sha256").update(canonical, "utf8").digest("hex");
}

function plain(value: string): string {
  return moneyText(value);
}
