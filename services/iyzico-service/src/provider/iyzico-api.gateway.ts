import { Inject, Injectable, Logger } from "@nestjs/common";
import { createHmac, timingSafeEqual } from "node:crypto";
import type { CreatePaymentRequest, ThreeDSCallback } from "../api/payment.dto.js";
import { APP_CONFIG, type AppConfig } from "../config.js";
import { IyzicoHttpClient, type IyzicoResponse } from "./iyzico-http.client.js";
import {
  type ChargeOutcome,
  type Expected,
  IyzicoGateway,
  type RefundOutcome,
  type ThreeDSStart,
} from "./iyzico.gateway.js";

const LOCALE = "tr";

/** Gerçek iyzico. conversationId = deneme numarası, basketId = sipariş numarası. */
@Injectable()
export class IyzicoApiGateway extends IyzicoGateway {
  private readonly log = new Logger(IyzicoApiGateway.name);
  private readonly http: IyzicoHttpClient;
  private readonly secretKey: string;

  constructor(@Inject(APP_CONFIG) private readonly config: AppConfig) {
    super();
    const { baseUrl, apiKey, secretKey, timeoutMs } = config.iyzico;
    this.secretKey = secretKey;
    this.http = new IyzicoHttpClient(baseUrl, apiKey, secretKey, timeoutMs);
  }

  isConfigured(): boolean {
    return this.config.iyzico.apiKey !== "" && this.config.iyzico.secretKey !== "";
  }

  async charge(attemptId: string, request: CreatePaymentRequest): Promise<ChargeOutcome> {
    const result = await this.http.post("/payment/auth", paymentBody(attemptId, request));
    return this.outcome(result, this.isResponseSigned(result), expectedOf(attemptId, request));
  }

  async startThreeDS(attemptId: string, request: CreatePaymentRequest): Promise<ThreeDSStart> {
    const body = { ...paymentBody(attemptId, request), callbackUrl: this.config.iyzico.callbackUrl };
    const result = await this.http.post("/payment/3dsecure/initialize", body);
    const html = decodeHtml(result.threeDSHtmlContent);
    if (!isSuccess(result) || !html) {
      return { started: false, iyzicoPaymentId: null, htmlContent: null, message: errorMessage(result) };
    }
    return { started: true, iyzicoPaymentId: str(result.paymentId), htmlContent: html, message: null };
  }

  async completeThreeDS(expected: Expected, iyzicoPaymentId: string, conversationData: string | undefined): Promise<ChargeOutcome> {
    const result = await this.http.post("/payment/3dsecure/auth", {
      locale: LOCALE,
      conversationId: expected.attemptId,
      paymentId: iyzicoPaymentId,
      conversationData,
    });
    const signed = this.isResponseSigned(result) && str(result.paymentId) === iyzicoPaymentId;
    return this.outcome(result, signed, expected);
  }

  async inquire(expected: Expected, iyzicoPaymentId: string | null): Promise<ChargeOutcome> {
    const result = await this.http.post("/payment/detail", {
      locale: LOCALE,
      conversationId: expected.attemptId,
      ...(iyzicoPaymentId ? { paymentId: iyzicoPaymentId } : { paymentConversationId: expected.attemptId }),
    });
    if (!isSuccess(result)) {
      // Bulunamadı veya iyzico cevap veremedi: "çekilmedi" demek için yeterli değil.
      return { result: "UNCERTAIN", iyzicoPaymentId, message: errorMessage(result) };
    }
    const paymentStatus = str(result.paymentStatus)?.toUpperCase();
    if (paymentStatus === "FAILURE") {
      return { result: "DECLINED", iyzicoPaymentId: str(result.paymentId), message: "iyzico ödemeyi başarısız olarak kaydetmiş." };
    }
    if (paymentStatus !== "SUCCESS") {
      return { result: "UNCERTAIN", iyzicoPaymentId: str(result.paymentId), message: `iyzico ödeme durumu: ${paymentStatus}` };
    }
    return this.outcome(result, this.isResponseSigned(result), expected);
  }

  async refund(iyzicoPaymentId: string, amount: string, clientIp: string, conversationId: string): Promise<RefundOutcome> {
    const result = await this.http.post("/v2/payment/refund", {
      locale: LOCALE,
      conversationId,
      paymentId: iyzicoPaymentId,
      price: formatPrice(amount),
      currency: "TRY",
      ip: clientIp,
    });
    return isSuccess(result) ? { refunded: true, message: null } : { refunded: false, message: errorMessage(result) };
  }

  /**
   * iyzico 3D dönüş imzası: HMAC-SHA256(secretKey, "conversationData:conversationId:mdStatus:paymentId:status"),
   * küçük harf hex. Sabit sürede karşılaştırılır.
   */
  isCallbackSignatureValid(callback: ThreeDSCallback): boolean {
    if (!callback.signature) {
      return false;
    }
    const expected = this.hmac([
      callback.conversationData,
      callback.conversationId,
      callback.mdStatus,
      callback.paymentId,
      callback.status,
    ]);
    return safeEqual(expected, callback.signature.toLowerCase());
  }

  /**
   * Ödeme cevabının imzası: HMAC-SHA256 ile
   * "paymentId:currency:basketId:conversationId:paidPrice:price" (fiyatlar sondaki sıfırlar atılmış).
   */
  private isResponseSigned(result: IyzicoResponse): boolean {
    const signature = str(result.signature);
    if (!signature) {
      return false;
    }
    const expected = this.hmac([
      str(result.paymentId),
      str(result.currency),
      str(result.basketId),
      str(result.conversationId),
      plainNumber(result.paidPrice),
      plainNumber(result.price),
    ]);
    return safeEqual(expected, signature.toLowerCase());
  }

  /**
   * Ödeme cevabını yorumlar. status=success ve fraudStatus=1 paranın alındığını gösterir;
   * fraudStatus=0 iyzico'nun incelemesi sürüyor demektir (UNCERTAIN).
   */
  private outcome(result: IyzicoResponse, signatureValid: boolean, expected: Expected): ChargeOutcome {
    const paymentId = str(result.paymentId);
    if (!isSuccess(result)) {
      return { result: "DECLINED", iyzicoPaymentId: paymentId, message: errorMessage(result) };
    }
    if (!signatureValid || !matches(result, expected)) {
      this.log.warn(`iyzico cevabı doğrulanamadı (imza veya tutar tutmadı): deneme ${expected.attemptId}`);
      return { result: "UNCERTAIN", iyzicoPaymentId: paymentId, message: "iyzico cevabı doğrulanamadı." };
    }
    if (result.fraudStatus === 0) {
      return { result: "UNCERTAIN", iyzicoPaymentId: paymentId, message: "iyzico fraud incelemesi sürüyor." };
    }
    const paymentStatus = str(result.paymentStatus);
    const statusOk = paymentStatus == null || paymentStatus.toUpperCase() === "SUCCESS";
    if (result.fraudStatus === 1 && statusOk) {
      return { result: "PAID", iyzicoPaymentId: paymentId, message: null };
    }
    return { result: "DECLINED", iyzicoPaymentId: paymentId, message: "iyzico ödemeyi onaylamadı." };
  }

  private hmac(parts: (string | null | undefined)[]): string {
    return createHmac("sha256", this.secretKey)
      .update(parts.map((part) => part ?? "").join(":"))
      .digest("hex");
  }
}

function paymentBody(attemptId: string, request: CreatePaymentRequest) {
  const address = {
    contactName: request.address.contactName,
    city: request.address.city,
    country: "Turkey",
    address: request.address.address,
  };
  return {
    locale: LOCALE,
    conversationId: attemptId,
    price: formatPrice(request.amount),
    paidPrice: formatPrice(request.amount),
    installment: 1,
    paymentChannel: "WEB",
    basketId: String(request.orderId),
    paymentGroup: "PRODUCT",
    currency: "TRY",
    paymentCard: {
      cardHolderName: request.card.holderName.trim(),
      cardNumber: request.card.number,
      expireYear: request.card.expireYear,
      expireMonth: request.card.expireMonth,
      cvc: request.card.cvc,
      registerCard: 0,
    },
    buyer: {
      id: request.buyer.id,
      name: request.buyer.firstName,
      surname: request.buyer.lastName,
      identityNumber: request.buyer.identityNumber,
      email: request.buyer.email.trim(),
      gsmNumber: request.buyer.phone,
      registrationAddress: request.buyer.address,
      city: request.buyer.city,
      country: "Turkey",
      ip: request.clientIp,
    },
    shippingAddress: address,
    billingAddress: address,
    basketItems: request.items.map((item) => ({
      id: item.id,
      name: item.name,
      category1: "Ürün",
      category2: "Genel",
      itemType: "PHYSICAL",
      price: formatPrice(item.price),
    })),
  };
}

function matches(result: IyzicoResponse, expected: Expected): boolean {
  const price = Number(result.price);
  const paidPrice = Number(result.paidPrice);
  const amount = Number(expected.amount);
  return (
    str(result.basketId) === String(expected.orderId) &&
    str(result.conversationId) === expected.attemptId &&
    str(result.currency) === "TRY" &&
    Number.isFinite(price) &&
    Math.round(price * 100) === Math.round(amount * 100) &&
    Math.round(paidPrice * 100) === Math.round(amount * 100)
  );
}

function expectedOf(attemptId: string, request: CreatePaymentRequest): Expected {
  return { attemptId, orderId: request.orderId, amount: request.amount.toFixed(2) };
}

/** iyzico'nun beklediği fiyat biçimi (resmî SDK ile aynı): 12.5 → "12.5", 5 → "5.0" */
export function formatPrice(value: number | string): string {
  const text = parseFloat(String(value)).toString();
  return text.includes(".") ? text : `${text}.0`;
}

/** İmza için: sondaki sıfırlar atılmış düz sayı (Java BigDecimal.stripTrailingZeros ile aynı). */
function plainNumber(value: unknown): string {
  if (value == null) {
    return "";
  }
  const text = String(value);
  if (!text.includes(".")) {
    return text;
  }
  return text.replace(/0+$/, "").replace(/\.$/, "");
}

function decodeHtml(content: unknown): string | null {
  const text = str(content)?.trim();
  if (!text) {
    return null;
  }
  if (text.startsWith("<")) {
    return text;
  }
  return Buffer.from(text, "base64").toString("utf8");
}

function isSuccess(result: IyzicoResponse): boolean {
  return str(result.status)?.toLowerCase() === "success";
}

function errorMessage(result: IyzicoResponse): string | null {
  return str(result.errorMessage);
}

function str(value: unknown): string | null {
  return value == null ? null : String(value);
}

function safeEqual(a: string, b: string): boolean {
  const left = Buffer.from(a, "utf8");
  const right = Buffer.from(b, "utf8");
  return left.length === right.length && timingSafeEqual(left, right);
}
