import { AttemptStatus } from "../generated/prisma/enums.js";

export { AttemptStatus };

/**
 * Ödeme denemesinin durum makinesi.
 *
 *   2D:  CREATED → SENT → SUCCEEDED | FAILED | VERIFYING
 *   3D:  CREATED → SENT → AWAITING_3DS → CONFIRM_SENT → SUCCEEDED | FAILED | VERIFYING
 *        VERIFYING → SUCCEEDED | FAILED | REFUNDED   (iyzico'ya sorularak çözülür)
 *        SUCCEEDED → REFUNDED
 *
 * SENT ve CONFIRM_SENT "iyzico'ya para çekme isteği gitti" demektir. Bu durumlarda
 * cevap gelmezse istek tekrar gönderilmez; deneme VERIFYING olur ve iyzico'ya sorulur.
 */
const NEXT: Record<AttemptStatus, readonly AttemptStatus[]> = {
  CREATED: ["SENT", "FAILED"],
  SENT: ["AWAITING_3DS", "SUCCEEDED", "FAILED", "VERIFYING"],
  AWAITING_3DS: ["CONFIRM_SENT", "FAILED"],
  CONFIRM_SENT: ["SUCCEEDED", "FAILED", "VERIFYING"],
  VERIFYING: ["SUCCEEDED", "FAILED", "REFUNDED"],
  SUCCEEDED: ["REFUNDED"],
  FAILED: [],
  REFUNDED: [],
};

export function canMoveTo(from: AttemptStatus, to: AttemptStatus): boolean {
  return NEXT[from].includes(to);
}

/** Siparişi meşgul eden durumlar. Veritabanında sipariş başına en fazla bir tane olabilir. */
export const ACTIVE: readonly AttemptStatus[] = ["CREATED", "SENT", "AWAITING_3DS", "CONFIRM_SENT", "VERIFYING"];

/** Henüz para çekme isteği gitmemiş aktif durumlar: yeni bir deneme bunları güvenle iptal edebilir. */
export const NOT_CHARGED_YET: readonly AttemptStatus[] = ["CREATED", "AWAITING_3DS"];

/** Bu durumdaki bir denemeyi aynı anahtarla tekrar sormak "hâlâ işleniyor" cevabı alır. */
export const IN_FLIGHT: readonly AttemptStatus[] = ["CREATED", "SENT", "AWAITING_3DS", "CONFIRM_SENT"];
