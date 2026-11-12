import { Injectable, Logger } from "@nestjs/common";
import { ApiProblem } from "../api/api-problem.js";
import type { CreatePaymentRequest, PaymentAttemptView, ThreeDSCallback } from "../api/payment.dto.js";
import { type AttemptStatus, IN_FLIGHT } from "../attempt/attempt-status.js";
import {
  AttemptNotFoundError,
  AttemptStore,
  isUniqueViolation,
  type PaymentAttempt,
  record,
  type Transition,
} from "../attempt/attempt.store.js";
import {
  type ChargeOutcome,
  type Expected,
  IyzicoGateway,
  ProviderNoAnswerError,
  ProviderNotReachedError,
} from "../provider/iyzico.gateway.js";
import { BackgroundWork } from "./background-work.js";
import { requestFingerprint } from "./request-fingerprint.js";

export interface StartResult {
  view: PaymentAttemptView;
  replayed: boolean;
  /** true: istek kabul edildi, sonuç arka planda belli olacak (HTTP 202). */
  accepted: boolean;
}

export interface StartOptions {
  /**
   * 2D ödemeyi beklemeden kabul et: deneme kaydedilir, hemen dönülür, iyzico arka planda çağrılır.
   * Sonuç RabbitMQ olayıyla duyurulur. 3D Secure her zaman senkrondur (banka sayfası hemen gerekir).
   */
  async?: boolean;
}

/**
 * Ödeme akışı. Her adımda önce durum veritabanına yazılır, sonra iyzico çağrılır,
 * sonra sonuç yazılır. Böylece süreç yarıda kesilse bile "iyzico'ya istek gitti mi?"
 * sorusunun cevabı veritabanında durur.
 */
@Injectable()
export class PaymentAttemptService {
  private readonly log = new Logger(PaymentAttemptService.name);

  constructor(
    private readonly store: AttemptStore,
    private readonly gateway: IyzicoGateway,
    private readonly background: BackgroundWork,
  ) {}

  // ---------------------------------------------------------------- başlatma

  async start(idempotencyKey: string, traceId: string, request: CreatePaymentRequest,
              options: StartOptions = {}): Promise<StartResult> {
    const runAsync = options.async === true && !request.threeDS;
    if (!this.gateway.isConfigured()) {
      throw ApiProblem.notConfigured();
    }
    requireBasketMatchesAmount(request);
    const fingerprint = requestFingerprint(request);

    const existing = await this.store.findByIdempotencyKey(idempotencyKey);
    if (existing) {
      return this.replay(existing, fingerprint, runAsync);
    }

    let attempt: PaymentAttempt;
    try {
      attempt = await this.store.create({
        orderId: request.orderId,
        idempotencyKey,
        requestHash: fingerprint,
        amount: request.amount.toFixed(2),
        threeDS: request.threeDS,
        traceId,
      });
    } catch (error) {
      if (!isUniqueViolation(error)) {
        throw error;
      }
      // Ya aynı anahtar az önce başka bir istekle yazıldı (çift tıklama),
      // ya da siparişin para çekme aşamasında başka bir denemesi var.
      const same = await this.store.findByIdempotencyKey(idempotencyKey);
      if (same) {
        return this.replay(same, fingerprint, runAsync);
      }
      throw ApiProblem.orderHasActiveAttempt(request.orderId);
    }
    this.log.log(`Deneme ${attempt.id} oluşturuldu: sipariş ${request.orderId}, ${request.amount} TL, 3D=${request.threeDS}, trace ${traceId}`);

    if (runAsync) {
      // Kart yalnızca bu kapanışın (closure) içinde, bellekte yaşar; veritabanına yazılmaz.
      // Süreç iyzico çağrısından önce ölürse deneme CREATED kalır: para çekilmemiştir,
      // kullanıcı yeniden dener (yeni deneme eskisini iptal eder).
      this.background.run(`deneme ${attempt.id}`, () => this.sendAndCharge(attempt.id, request));
      return { view: toView(attempt), replayed: false, accepted: true };
    }
    const view = await this.sendAndCharge(attempt.id, request);
    return { view, replayed: false, accepted: false };
  }

  /** CREATED → SENT, sonra 2D'de parayı çeker, 3D'de banka sayfasını alır. */
  private async sendAndCharge(id: string, request: CreatePaymentRequest): Promise<PaymentAttemptView> {
    const sent = await this.store.move(id, "CREATED", { to: "SENT", note: "iyzico'ya gönderiliyor" });
    if (!sent) {
      // Bu arada aynı sipariş için daha yeni bir deneme başladı ve bunu iptal etti.
      return toView(await this.store.get(id));
    }
    return request.threeDS ? this.startThreeDS(id, request) : this.charge(id, request);
  }

  private replay(attempt: PaymentAttempt, fingerprint: string, runAsync: boolean): StartResult {
    if (attempt.requestHash !== fingerprint) {
      throw ApiProblem.idempotencyKeyReused();
    }
    if (IN_FLIGHT.includes(attempt.status)) {
      if (runAsync) {
        // Asenkron istemci için "hâlâ işleniyor" bir hata değil: aynı denemeyi tekrar gösteririz.
        return { view: toView(attempt), replayed: true, accepted: true };
      }
      throw ApiProblem.paymentInProgress();
    }
    this.log.log(`Deneme ${attempt.id} için tekrar gelen istek; iyzico'ya gidilmedi, kayıtlı sonuç döndü`);
    return { view: toView(attempt), replayed: true, accepted: false };
  }

  // ---------------------------------------------------------------- 2D

  private async charge(id: string, request: CreatePaymentRequest): Promise<PaymentAttemptView> {
    let outcome: ChargeOutcome;
    try {
      outcome = await this.gateway.charge(id, request);
    } catch (error) {
      if (error instanceof ProviderNotReachedError) {
        return this.finish(id, "SENT", { to: "FAILED", note: "iyzico'ya ulaşılamadı", failureReason: error.message });
      }
      if (error instanceof ProviderNoAnswerError) {
        return this.verifyAfterLostAnswer(id, "SENT");
      }
      throw error;
    }
    return this.applyOutcome(id, "SENT", outcome);
  }

  // ---------------------------------------------------------------- 3D Secure

  private async startThreeDS(id: string, request: CreatePaymentRequest): Promise<PaymentAttemptView> {
    let start;
    try {
      start = await this.gateway.startThreeDS(id, request);
    } catch (error) {
      if (error instanceof ProviderNotReachedError || error instanceof ProviderNoAnswerError) {
        // 3D başlatma para çekmez; cevap gelmese bile güvenle başarısız sayılır.
        return this.finish(id, "SENT", { to: "FAILED", note: "3D başlatılamadı", failureReason: error.message });
      }
      throw error;
    }
    if (!start.started) {
      return this.finish(id, "SENT", {
        to: "FAILED",
        note: "3D başlatılamadı",
        failureReason: start.message || "3D Secure doğrulaması başlatılamadı.",
      });
    }
    const waiting = await this.store.move(id, "SENT", {
      to: "AWAITING_3DS",
      note: "Banka SMS doğrulaması bekleniyor",
      iyzicoPaymentId: start.iyzicoPaymentId,
    });
    return waiting ? toView(waiting, start.htmlContent) : toView(await this.store.get(id));
  }

  /** iyzico'nun 3D dönüşü. Aynı dönüş iki kez gelirse ikincisi hiçbir şey yapmaz. */
  async completeThreeDS(id: string, callback: ThreeDSCallback): Promise<PaymentAttemptView> {
    const attempt = await this.store.get(id);
    if (callback.conversationId !== id || !attempt.iyzicoPaymentId || attempt.iyzicoPaymentId !== callback.paymentId) {
      throw ApiProblem.callbackRejected("Dönüş bu ödeme denemesiyle eşleşmiyor.");
    }
    if (!callback.signature) {
      this.log.warn(`3D dönüşü imzasız geldi: deneme ${id}. Sonuç yine de iyzico'nun imzalı cevabıyla doğrulanacak.`);
    } else if (!this.gateway.isCallbackSignatureValid(callback)) {
      throw ApiProblem.callbackRejected("3D dönüşünün imzası geçersiz.");
    }

    if (attempt.status !== "AWAITING_3DS") {
      return toView(attempt);
    }
    if (callback.status?.toLowerCase() !== "success" || callback.mdStatus !== "1") {
      return this.finish(id, "AWAITING_3DS", {
        to: "FAILED",
        note: `Banka doğrulaması geçmedi (mdStatus=${callback.mdStatus})`,
        failureReason: "3D Secure doğrulaması başarısız.",
      });
    }
    const confirming = await this.store.move(id, "AWAITING_3DS", {
      to: "CONFIRM_SENT",
      note: "SMS onaylandı, para çekme isteği gönderiliyor",
    });
    if (!confirming) {
      return toView(await this.store.get(id)); // diğer dönüş bizden önce davrandı
    }

    let outcome: ChargeOutcome;
    try {
      outcome = await this.gateway.completeThreeDS(expectedOf(attempt), attempt.iyzicoPaymentId, callback.conversationData);
    } catch (error) {
      if (error instanceof ProviderNotReachedError) {
        return this.finish(id, "CONFIRM_SENT", { to: "FAILED", note: "iyzico'ya ulaşılamadı", failureReason: error.message });
      }
      if (error instanceof ProviderNoAnswerError) {
        return this.verifyAfterLostAnswer(id, "CONFIRM_SENT");
      }
      throw error;
    }
    return this.applyOutcome(id, "CONFIRM_SENT", outcome);
  }

  // ---------------------------------------------------------------- doğrulama

  /** VERIFYING durumundaki denemeyi iyzico'ya sorar. Cevap kesin değilse VERIFYING kalır. */
  async verify(id: string): Promise<PaymentAttemptView> {
    const attempt = await this.store.get(id);
    if (attempt.status !== "VERIFYING") {
      return toView(attempt);
    }
    let outcome: ChargeOutcome;
    try {
      outcome = await this.gateway.inquire(expectedOf(attempt), attempt.iyzicoPaymentId);
    } catch (error) {
      if (error instanceof ProviderNotReachedError || error instanceof ProviderNoAnswerError) {
        this.log.warn(`Deneme ${id} iyzico'ya sorulamadı: ${error.message}`);
        return toView(attempt);
      }
      throw error;
    }
    if (outcome.result === "UNCERTAIN") {
      this.log.log(`Deneme ${id} hâlâ belirsiz: ${outcome.message}`);
      return toView(attempt);
    }
    return this.applyOutcome(id, "VERIFYING", outcome);
  }

  /**
   * Mutabakat işi çağırır: bir deneme uzun süredir ara durumda kaldıysa (ör. servis iyzico çağrısının
   * ortasında yeniden başladıysa) güvenli şekilde sonuca bağlar. Hiçbir zaman kör tekrar yapmaz.
   */
  async recoverStuck(attempt: PaymentAttempt): Promise<PaymentAttemptView> {
    const id = attempt.id;
    switch (attempt.status) {
      case "CREATED":
        // İstek iyzico'ya hiç gitmedi: para çekilmedi.
        return this.finish(id, "CREATED", {
          to: "FAILED", note: "Mutabakat: istek hiç gönderilmedi", failureReason: "Ödeme başlatılamadı, lütfen tekrar deneyin.",
        });
      case "SENT":
        if (attempt.threeDS) {
          // 3D başlatma para çekmez: cevabı kaybolsa da güvenle başarısız sayılır.
          return this.finish(id, "SENT", {
            to: "FAILED", note: "Mutabakat: 3D başlatma cevabı alınamadı", failureReason: "3D Secure başlatılamadı.",
          });
        }
        return this.verifyAfterLostAnswer(id, "SENT");
      case "CONFIRM_SENT":
        return this.verifyAfterLostAnswer(id, "CONFIRM_SENT");
      case "AWAITING_3DS":
        // Kullanıcı SMS ekranını bıraktı; para çekme isteği hiç gitmedi.
        return this.finish(id, "AWAITING_3DS", {
          to: "FAILED", note: "Mutabakat: 3D doğrulaması tamamlanmadı", failureReason: "3D Secure doğrulaması zamanında tamamlanmadı.",
        });
      case "VERIFYING":
        return this.verify(id);
      default:
        return toView(attempt);
    }
  }

  private async verifyAfterLostAnswer(id: string, from: AttemptStatus): Promise<PaymentAttemptView> {
    this.log.warn(`Deneme ${id} için iyzico'dan cevap gelmedi; tekrar gönderilmeyecek, iyzico'ya sorulacak`);
    await this.store.move(id, from, {
      to: "VERIFYING",
      note: "iyzico'dan cevap gelmedi",
      failureReason: "Ödeme sonucu iyzico'dan doğrulanıyor.",
    });
    return this.verify(id);
  }

  // ---------------------------------------------------------------- iade

  /** Satır kilitli kalırken iade edilir: aynı ödeme iki kez iade edilemez. */
  async refund(id: string, clientIp: string): Promise<PaymentAttemptView> {
    return this.store.withLock(id, async (attempt, tx) => {
      const moneyMayBeTaken = attempt.status === "SUCCEEDED" || attempt.status === "VERIFYING";
      if (!moneyMayBeTaken || !attempt.iyzicoPaymentId) {
        throw ApiProblem.notRefundable(`Durumu ${attempt.status} olan deneme iade edilemez.`);
      }
      const amount = attempt.amount.toFixed(2);
      await this.refundAtProvider(attempt.iyzicoPaymentId, amount, clientIp, id);
      const refunded = await record(tx, attempt, { to: "REFUNDED", note: `${amount} TL iade edildi` });
      this.log.log(`Deneme ${id} (sipariş ${attempt.orderId}) iade edildi`);
      return toView(refunded);
    });
  }

  /** IyzicoService'ten önce alınmış ödemeler için: denemesi olmayan iyzico ödemesini iade eder. */
  async refundLegacy(iyzicoPaymentId: string, amount: number, clientIp: string, orderId: number): Promise<void> {
    await this.refundAtProvider(iyzicoPaymentId, amount.toFixed(2), clientIp, String(orderId));
    this.log.log(`Eski ödeme ${iyzicoPaymentId} (sipariş ${orderId}) iade edildi`);
  }

  private async refundAtProvider(iyzicoPaymentId: string, amount: string, clientIp: string, conversationId: string) {
    if (!this.gateway.isConfigured()) {
      throw ApiProblem.notConfigured();
    }
    let outcome;
    try {
      outcome = await this.gateway.refund(iyzicoPaymentId, amount, clientIp, conversationId);
    } catch (error) {
      if (error instanceof ProviderNotReachedError || error instanceof ProviderNoAnswerError) {
        throw ApiProblem.providerFailed("İade sırasında iyzico'ya ulaşılamadı. iyzico panelinden kontrol edin.");
      }
      throw error;
    }
    if (!outcome.refunded) {
      throw ApiProblem.providerFailed(outcome.message || "İade yapılamadı.");
    }
  }

  async get(id: string): Promise<PaymentAttemptView> {
    return toView(await this.store.get(id));
  }

  // ---------------------------------------------------------------- yardımcılar

  private async applyOutcome(id: string, from: AttemptStatus, outcome: ChargeOutcome): Promise<PaymentAttemptView> {
    const transition = transitionFor(outcome);
    if (transition.to === from) {
      return toView(await this.store.get(id));
    }
    try {
      return await this.finish(id, from, transition);
    } catch (error) {
      if (!isUniqueViolation(error)) {
        throw error;
      }
      // Veritabanı "siparişte tek başarılı ödeme" kuralını korudu: bu ikinci bir başarılı ödeme.
      // Para çekildi; deneme incelemeye alınır (iade mutabakat adımında otomatik olacak).
      this.log.error(`Sipariş için ikinci başarılı ödeme! deneme ${id}`);
      if (from === "VERIFYING") {
        return toView(await this.store.get(id));
      }
      return this.finish(id, from, {
        to: "VERIFYING",
        note: "Siparişin zaten başarılı bir ödemesi var",
        iyzicoPaymentId: outcome.iyzicoPaymentId,
        failureReason: "Aynı sipariş için ikinci başarılı ödeme; iade edilmeli.",
      });
    }
  }

  private async finish(id: string, from: AttemptStatus, transition: Transition): Promise<PaymentAttemptView> {
    const moved = await this.store.move(id, from, transition);
    return toView(moved ?? (await this.store.get(id)));
  }
}

export function toView(attempt: PaymentAttempt, threeDSHtmlContent: string | null = null): PaymentAttemptView {
  return {
    attemptId: attempt.id,
    orderId: Number(attempt.orderId),
    status: attempt.status,
    amount: attempt.amount.toNumber(),
    currency: attempt.currency,
    threeDS: attempt.threeDS,
    iyzicoPaymentId: attempt.iyzicoPaymentId,
    failureReason: attempt.failureReason,
    traceId: attempt.traceId,
    threeDSHtmlContent,
  };
}

function transitionFor(outcome: ChargeOutcome): Transition {
  const iyzicoPaymentId = outcome.iyzicoPaymentId;
  switch (outcome.result) {
    case "PAID":
      return { to: "SUCCEEDED", note: "Ödeme alındı", iyzicoPaymentId };
    case "DECLINED":
      return { to: "FAILED", note: "Ödeme reddedildi", iyzicoPaymentId, failureReason: outcome.message || "Ödeme alınamadı." };
    case "UNCERTAIN":
      return { to: "VERIFYING", note: `Sonuç belirsiz: ${outcome.message}`, iyzicoPaymentId, failureReason: outcome.message };
  }
}

function expectedOf(attempt: PaymentAttempt): Expected {
  return { attemptId: attempt.id, orderId: Number(attempt.orderId), amount: attempt.amount.toFixed(2) };
}

function requireBasketMatchesAmount(request: CreatePaymentRequest): void {
  const cents = (value: number) => Math.round(value * 100);
  const sum = request.items.reduce((total, item) => total + cents(item.price), 0);
  if (sum !== cents(request.amount)) {
    throw ApiProblem.basketTotalMismatch();
  }
}

export { AttemptNotFoundError };
