import type { CreatePaymentRequest, ThreeDSCallback } from "../api/payment.dto.js";

/**
 * iyzico ile konuşan tek yer. Testlerde sahtesi kullanılır.
 *
 * Ağ hataları iki türlüdür ve farkı önemlidir:
 *  - ProviderNotReachedError: istek iyzico'ya hiç ulaşmadı. Para çekilmedi.
 *  - ProviderNoAnswerError: istek gitti ama cevap gelmedi. Para çekilmiş olabilir;
 *    tekrar göndermek yerine inquire() ile sorulmalı.
 */
export abstract class IyzicoGateway {
  abstract isConfigured(): boolean;

  /** 2D ödeme: kartı tek adımda çeker. */
  abstract charge(attemptId: string, request: CreatePaymentRequest): Promise<ChargeOutcome>;

  /** 3D Secure'u başlatır. Para çekmez; bankanın SMS sayfasını döner. */
  abstract startThreeDS(attemptId: string, request: CreatePaymentRequest): Promise<ThreeDSStart>;

  /** SMS onayından sonra parayı çeker. */
  abstract completeThreeDS(expected: Expected, iyzicoPaymentId: string, conversationData: string | undefined): Promise<ChargeOutcome>;

  /** "Bu ödemenin sonucu ne oldu?" diye sorar. Kesin değilse UNCERTAIN döner. */
  abstract inquire(expected: Expected, iyzicoPaymentId: string | null): Promise<ChargeOutcome>;

  abstract refund(iyzicoPaymentId: string, amount: string, clientIp: string, conversationId: string): Promise<RefundOutcome>;

  /** 3D dönüşündeki imza geçerli mi? İmza alanı yoksa false. */
  abstract isCallbackSignatureValid(callback: ThreeDSCallback): boolean;
}

/** iyzico cevabının ait olması gereken deneme. Cevap bununla eşleşmezse UNCERTAIN sayılır. */
export interface Expected {
  attemptId: string;
  orderId: number;
  /** "12.50" gibi */
  amount: string;
}

export type ChargeResult =
  /** Para çekildi ve cevap doğrulandı. */
  | "PAID"
  /** Kart reddedildi; para çekilmedi. */
  | "DECLINED"
  /** Para çekilmiş olabilir ama emin değiliz (fraud incelemesi, imza tutmadı, cevap belirsiz). */
  | "UNCERTAIN";

export interface ChargeOutcome {
  result: ChargeResult;
  iyzicoPaymentId: string | null;
  message: string | null;
}

export interface ThreeDSStart {
  started: boolean;
  iyzicoPaymentId: string | null;
  htmlContent: string | null;
  message: string | null;
}

export interface RefundOutcome {
  refunded: boolean;
  message: string | null;
}

/** İstek iyzico'ya hiç ulaşmadı (bağlantı veya TLS el sıkışması kurulamadı). Para çekilmediği kesin. */
export class ProviderNotReachedError extends Error {}

/** İstek iyzico'ya gitti ama cevap alınamadı. Para çekilmiş olabilir: kör tekrar yapılmaz, sorulur. */
export class ProviderNoAnswerError extends Error {}
