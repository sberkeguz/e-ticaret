import type { CreatePaymentRequest, ThreeDSCallback } from "../src/api/payment.dto.js";
import {
  type ChargeOutcome,
  type CheckoutFormStart,
  type ChargeResult,
  type Expected,
  IyzicoGateway,
  ProviderNoAnswerError,
  ProviderNotReachedError,
  type RefundOutcome,
  type ThreeDSStart,
} from "../src/provider/iyzico.gateway.js";

/**
 * Testlerde iyzico yerine geçer. Her çağrıyı sayar; senaryoya göre cevabı değiştirilebilir.
 * Varsayılan: her şey başarılı.
 */
export class FakeIyzicoGateway extends IyzicoGateway {
  charges = 0;
  threeDSStarts = 0;
  checkoutFormStarts = 0;
  checkoutFormRetrieves = 0;
  /** Ödeme Formu sorgusunun sonucu */
  checkoutFormResult: ChargeResult = "PAID";
  threeDSCompletions = 0;
  inquiries = 0;
  refunds = 0;

  /** true: iyzico parayı çeker ama cevap yolda kaybolur. */
  dropChargeAnswer = false;
  /** true: iyzico'ya bağlanılamaz (istek hiç gitmez). */
  unreachable = false;
  /** true: kart reddedilir. */
  declineCharges = false;
  /** inquire() ne desin. */
  inquiryResult: ChargeResult = "PAID";
  /** Çağrıyı yavaşlatır; eşzamanlılık testlerinde yarışı büyütür. */
  delayMs = 0;
  callbackSignatureValid = true;

  reset(): void {
    Object.assign(this, new FakeIyzicoGateway());
  }

  isConfigured(): boolean {
    return true;
  }

  async charge(attemptId: string, _request: CreatePaymentRequest): Promise<ChargeOutcome> {
    if (this.unreachable) {
      throw new ProviderNotReachedError("connect ECONNREFUSED 127.0.0.1:443");
    }
    this.charges++;
    await this.pause();
    if (this.dropChargeAnswer) {
      throw new ProviderNoAnswerError("test: cevap düştü");
    }
    if (this.declineCharges) {
      return { result: "DECLINED", iyzicoPaymentId: null, message: "Kart limiti yetersiz" };
    }
    return { result: "PAID", iyzicoPaymentId: `pay-${attemptId}`, message: null };
  }

  async startThreeDS(attemptId: string, _request: CreatePaymentRequest): Promise<ThreeDSStart> {
    this.threeDSStarts++;
    return { started: true, iyzicoPaymentId: `3ds-${attemptId}`, htmlContent: "<html>banka</html>", message: null };
  }

  async startCheckoutForm(attemptId: string, _request: CreatePaymentRequest): Promise<CheckoutFormStart> {
    this.checkoutFormStarts++;
    return { started: true, token: `cf-${attemptId}`, content: "<div id=\"iyzipay-checkout-form\"></div>", message: null };
  }

  async retrieveCheckoutForm(expected: Expected, _token: string): Promise<ChargeOutcome> {
    this.checkoutFormRetrieves++;
    await this.pause();
    const paid = this.checkoutFormResult !== "DECLINED";
    return { result: this.checkoutFormResult, iyzicoPaymentId: paid ? `cf-pay-${expected.attemptId}` : null, message: paid ? null : "Kart reddedildi" };
  }

  async completeThreeDS(_expected: Expected, iyzicoPaymentId: string): Promise<ChargeOutcome> {
    this.threeDSCompletions++;
    await this.pause();
    return { result: "PAID", iyzicoPaymentId, message: null };
  }

  async inquire(expected: Expected, iyzicoPaymentId: string | null): Promise<ChargeOutcome> {
    this.inquiries++;
    return { result: this.inquiryResult, iyzicoPaymentId: iyzicoPaymentId ?? `pay-${expected.attemptId}`, message: "test sorgusu" };
  }

  async refund(): Promise<RefundOutcome> {
    this.refunds++;
    return { refunded: true, message: null };
  }

  isCallbackSignatureValid(_callback: ThreeDSCallback): boolean {
    return this.callbackSignatureValid;
  }

  private async pause(): Promise<void> {
    if (this.delayMs > 0) {
      await new Promise((resolve) => setTimeout(resolve, this.delayMs));
    }
  }
}
