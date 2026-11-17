import { Injectable, Logger } from "@nestjs/common";
import { ApiProblem } from "../api/api-problem.js";
import type { CreatePaymentRequest, PaymentAttemptView, CheckoutFormCallback, ThreeDSCallback } from "../api/payment.dto.js";
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
import { money, moneyText, sumMoney } from "../money.js";
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
  /** Deneme numarası dışarıdan verilir (kuyrukla gelen 2D isteği: backend numarayı önceden atar). */
  attemptId?: string;
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
    const checkoutForm = request.mode === "checkout-form";
    // Yalnızca kartla 2D ödeme arka planda yapılır; 3D ve Ödeme Formu müşterinin ekranına içerik döner.
    const runAsync = options.async === true && !request.threeDS && !checkoutForm;
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
        amount: moneyText(request.amount),
        // Ödeme Formunda 3D doğrulamasını iyzico'nun formu yapar.
        threeDS: request.threeDS || checkoutForm,
        traceId,
        id: options.attemptId,
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
    this.log.log(`Deneme ${attempt.id} oluşturuldu: sipariş ${request.orderId}, ${request.amount} TL, ${checkoutForm ? "Ödeme Formu" : `3D=${request.threeDS}`}, trace ${traceId}`);

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
    if (request.mode === "checkout-form") {
      return this.startCheckoutForm(id, request);
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
        return this.finish(id, "SENT", {
          to: "FAILED", note: `iyzico'ya ulaşılamadı: ${error.message}`, failureReason: PROVIDER_UNREACHABLE,
        });
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
        return this.finish(id, "SENT", {
          to: "FAILED", note: `3D başlatılamadı: ${error.message}`, failureReason: PROVIDER_UNREACHABLE,
        });
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
      this.log.warn(
        `3D dönüşü eşleşmedi: deneme ${id} (iyzico ${attempt.iyzicoPaymentId ?? "-"}), ` +
          `gelen conversationId=${callback.conversationId ?? "-"} paymentId=${callback.paymentId ?? "-"} status=${callback.status ?? "-"}`,
      );
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
        return this.finish(id, "CONFIRM_SENT", {
          to: "FAILED", note: `iyzico'ya ulaşılamadı: ${error.message}`, failureReason: PROVIDER_UNREACHABLE,
        });
      }
      if (error instanceof ProviderNoAnswerError) {
        return this.verifyAfterLostAnswer(id, "CONFIRM_SENT");
      }
      throw error;
    }
    return this.applyOutcome(id, "CONFIRM_SENT", outcome);
  }

  /**
   * Kuyrukla gelen ama iyzico'ya gönderilmeyecek 2D isteği (süresi geçmiş veya geçersiz): deneme
   * başarısız olarak kaydedilir ve PaymentFailed olayı yayınlanır. Aynı istek tekrar gelirse bir şey yapmaz.
   */
  async rejectRequest(attemptId: string, idempotencyKey: string, orderId: number, amount: string,
                      traceId: string, reason: string): Promise<void> {
    if (await this.store.findByIdempotencyKey(idempotencyKey)) {
      return;
    }
    try {
      await this.store.create({
        id: attemptId, orderId, idempotencyKey, requestHash: "-".repeat(64), amount: moneyText(amount), threeDS: false, traceId,
      });
    } catch (error) {
      if (isUniqueViolation(error)) {
        return; // aynı istek ikinci kez geldi
      }
      throw error;
    }
    await this.finish(attemptId, "CREATED", { to: "FAILED", note: "Kuyruktaki istek reddedildi", failureReason: reason });
    this.log.warn(`Deneme ${attemptId} (sipariş ${orderId}) iyzico'ya gönderilmedi: ${reason}`);
  }

  // ---------------------------------------------------------------- iyzico Ödeme Formu

  /** Formu başlatır. Para çekmez; müşteri formu doldurana kadar deneme AWAITING_3DS'te bekler. */
  private async startCheckoutForm(id: string, request: CreatePaymentRequest): Promise<PaymentAttemptView> {
    let start;
    try {
      start = await this.gateway.startCheckoutForm(id, request);
    } catch (error) {
      if (error instanceof ProviderNotReachedError || error instanceof ProviderNoAnswerError) {
        return this.finish(id, "SENT", { to: "FAILED", note: "Ödeme formu açılamadı", failureReason: error.message });
      }
      throw error;
    }
    if (!start.started || !start.token) {
      return this.finish(id, "SENT", {
        to: "FAILED", note: "Ödeme formu açılamadı", failureReason: start.message || "Ödeme formu açılamadı.",
      });
    }
    const waiting = await this.store.move(id, "SENT", {
      to: "AWAITING_3DS",
      note: "Müşterinin iyzico ödeme formunu doldurması bekleniyor",
      checkoutToken: start.token,
    });
    return waiting ? toView(waiting, null, start.content) : toView(await this.store.get(id));
  }

  /**
   * Formun dönüşü: iyzico yalnızca token gönderir; sonuç imzalı cevapla iyzico'ya sorulur.
   * Aynı dönüş iki kez gelirse ikincisi hiçbir şey yapmaz.
   */
  async completeCheckoutForm(id: string, callback: CheckoutFormCallback): Promise<PaymentAttemptView> {
    const attempt = await this.store.get(id);
    if (!attempt.checkoutToken || attempt.checkoutToken !== callback.token) {
      this.log.warn(`Ödeme formu dönüşü eşleşmedi: deneme ${id}`);
      throw ApiProblem.callbackRejected("Dönüş bu ödeme denemesiyle eşleşmiyor.");
    }
    if (attempt.status !== "AWAITING_3DS") {
      return toView(attempt);
    }
    const confirming = await this.store.move(id, "AWAITING_3DS", {
      to: "CONFIRM_SENT",
      note: "Ödeme formu tamamlandı, sonuç iyzico'ya soruluyor",
    });
    if (!confirming) {
      return toView(await this.store.get(id));
    }
    let outcome: ChargeOutcome;
    try {
      outcome = await this.gateway.retrieveCheckoutForm(expectedOf(attempt), attempt.checkoutToken);
    } catch (error) {
      if (error instanceof ProviderNotReachedError || error instanceof ProviderNoAnswerError) {
        return this.verifyAfterLostAnswer(id, "CONFIRM_SENT");
      }
      throw error;
    }
    return this.applyOutcome(id, "CONFIRM_SENT", outcome);
  }

  /**
   * Mutabakat: müşteri formu açık bıraktı ya da dönüş bize ulaşmadı. Müşteri ödemiş olabilir; bu
   * yüzden önce iyzico'ya sorulur. Form süresi (30 dk) dolana kadar ödenmemişse beklemeye devam edilir.
   */
  private async recoverCheckoutForm(attempt: PaymentAttempt): Promise<PaymentAttemptView> {
    const id = attempt.id;
    let outcome: ChargeOutcome;
    try {
      outcome = await this.gateway.retrieveCheckoutForm(expectedOf(attempt), attempt.checkoutToken!);
    } catch (error) {
      if (error instanceof ProviderNotReachedError || error instanceof ProviderNoAnswerError) {
        return toView(attempt); // sonraki turda tekrar sorulur
      }
      throw error;
    }
    if (outcome.result === "PAID") {
      const confirming = await this.store.move(id, "AWAITING_3DS", { to: "CONFIRM_SENT", note: "Mutabakat: form ödenmiş" });
      return confirming ? this.applyOutcome(id, "CONFIRM_SENT", outcome) : toView(await this.store.get(id));
    }
    if (outcome.result === "DECLINED") {
      return this.finish(id, "AWAITING_3DS", {
        to: "FAILED", note: "Mutabakat: form ödemesi başarısız", failureReason: outcome.message || "Ödeme başarısız.",
      });
    }
    if (Date.now() - attempt.updatedAt.getTime() < CHECKOUT_FORM_LIFETIME_MS) {
      return toView(attempt);
    }
    return this.finish(id, "AWAITING_3DS", {
      to: "FAILED", note: "Mutabakat: ödeme formu zamanında doldurulmadı", failureReason: "Ödeme formu zamanında tamamlanmadı.",
    });
  }

  // ---------------------------------------------------------------- doğrulama

  /** VERIFYING durumundaki denemeyi iyzico'ya sorar. Cevap kesin değilse VERIFYING kalır. */
  async verify(id: string, now: Date = new Date()): Promise<PaymentAttemptView> {
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
    if (outcome.result === "NOT_FOUND") {
      // VERIFYING'e geçiş, isteğin gönderilmesinden en fazla zaman aşımı kadar sonradır; deneme o andan
      // beri değişmedi (updatedAt). Bu kadar süre sonra iyzico'da kayıt yoksa istek iyzico'ya hiç
      // ulaşmamıştır: para çekilmedi.
      const waitedMs = now.getTime() - attempt.updatedAt.getTime();
      if (waitedMs < NOT_FOUND_FINAL_AFTER_MS) {
        this.log.log(`Deneme ${id}: iyzico'da kayıt yok, ${Math.round(waitedMs / 1000)} sn oldu; karar için bekleniyor`);
        return toView(attempt);
      }
      return this.finish(id, "VERIFYING", {
        to: "FAILED",
        note: `iyzico'da ${Math.round(NOT_FOUND_FINAL_AFTER_MS / 60_000)} dk boyunca ödeme kaydı yok: istek ulaşmamış`,
        failureReason: "Ödeme tamamlanmadı; kartınızdan para çekilmedi. Lütfen tekrar deneyin.",
      });
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
        if (attempt.checkoutToken) {
          return this.recoverCheckoutForm(attempt);
        }
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
  async refundLegacy(iyzicoPaymentId: string, amount: string, clientIp: string, orderId: number): Promise<void> {
    await this.refundAtProvider(iyzicoPaymentId, moneyText(amount), clientIp, String(orderId));
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

/**
 * iyzico bir ödemenin kaydını bu süre boyunca bulamazsa istek ona hiç ulaşmamış sayılır. iyzico isteği
 * saniyeler içinde işler; zaman aşımımız 60 sn. 10 dk geniş bir güvenlik payıdır.
 */
export const NOT_FOUND_FINAL_AFTER_MS = 10 * 60_000;

/** iyzico'ya bağlanılamadığında müşteriye gösterilen sebep (teknik ayrıntı yalnız notta/logda). */
const PROVIDER_UNREACHABLE = "Ödeme sistemine şu an ulaşılamıyor; kartınızdan para çekilmedi. Lütfen biraz sonra tekrar deneyin.";

/** iyzico Ödeme Formu token'ı 30 dk geçerli; birkaç dakika pay bırakılır. */
const CHECKOUT_FORM_LIFETIME_MS = 35 * 60_000;

export function toView(
  attempt: PaymentAttempt,
  threeDSHtmlContent: string | null = null,
  checkoutFormContent: string | null = null,
): PaymentAttemptView {
  return {
    attemptId: attempt.id,
    orderId: Number(attempt.orderId),
    status: attempt.status,
    amount: attempt.amount.toFixed(2),
    currency: attempt.currency,
    threeDS: attempt.threeDS,
    iyzicoPaymentId: attempt.iyzicoPaymentId,
    failureReason: attempt.failureReason,
    traceId: attempt.traceId,
    threeDSHtmlContent,
    checkoutFormContent,
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
    case "NOT_FOUND":
      return { to: "VERIFYING", note: `Sonuç belirsiz: ${outcome.message}`, iyzicoPaymentId, failureReason: outcome.message };
  }
}

function expectedOf(attempt: PaymentAttempt): Expected {
  return { attemptId: attempt.id, orderId: Number(attempt.orderId), amount: attempt.amount.toFixed(2) };
}

function requireBasketMatchesAmount(request: CreatePaymentRequest): void {
  const sum = sumMoney(request.items.map((item) => item.price));
  if (!sum.equals(money(request.amount))) {
    throw ApiProblem.basketTotalMismatch();
  }
}

export { AttemptNotFoundError };
