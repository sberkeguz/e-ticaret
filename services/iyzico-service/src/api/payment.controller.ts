import { Body, Controller, Get, Headers, HttpCode, HttpStatus, Param, ParseUUIDPipe, Post, Res } from "@nestjs/common";
import { ApiHeader, ApiOperation, ApiResponse, ApiSecurity, ApiTags } from "@nestjs/swagger";
import type { Response } from "express";
import { PaymentAttemptService } from "../payment/payment-attempt.service.js";
import { ApiProblem } from "./api-problem.js";
import {
  CreatePaymentRequest,
  LegacyRefundRequest,
  PaymentAttemptView,
  RefundRequest,
  ThreeDSCallback,
} from "./payment.dto.js";
import { traceIdFrom } from "./trace-ids.js";

const IDEMPOTENCY_KEY = /^[A-Za-z0-9_-]{8,100}$/;

@ApiTags("Ödemeler")
@ApiSecurity("internal-token")
@Controller()
export class PaymentController {
  constructor(private readonly payments: PaymentAttemptService) {}

  @Post("payments")
  @ApiOperation({
    summary: "Ödeme başlat (2D veya 3D)",
    description:
      "Aynı Idempotency-Key ile tekrar gelen istek iyzico'ya gitmez: kayıtlı sonuç döner (200, Idempotent-Replayed: true). " +
      "İşlem sürüyorsa 409, anahtar farklı içerikle kullanıldıysa 422.",
  })
  @ApiHeader({ name: "Idempotency-Key", required: true, description: "Her ödeme denemesi için istemcinin ürettiği benzersiz anahtar" })
  @ApiResponse({ status: 201, type: PaymentAttemptView })
  @ApiResponse({ status: 200, type: PaymentAttemptView, description: "Aynı anahtarla tekrar gelen istek" })
  @ApiHeader({ name: "Prefer", required: false, description: "respond-async: 2D ödemeyi beklemeden kabul et (202). Sonuç RabbitMQ olayıyla gelir." })
  @ApiResponse({ status: 202, type: PaymentAttemptView, description: "Kabul edildi, sonuç arka planda (yalnızca 2D + Prefer: respond-async)" })
  async start(
    @Headers("idempotency-key") idempotencyKey: string | undefined,
    @Headers("traceparent") traceparent: string | undefined,
    @Headers("prefer") prefer: string | undefined,
    @Body() request: CreatePaymentRequest,
    @Res({ passthrough: true }) response: Response,
  ): Promise<PaymentAttemptView> {
    if (!idempotencyKey || !IDEMPOTENCY_KEY.test(idempotencyKey)) {
      throw ApiProblem.validation("Idempotency-Key başlığı zorunlu: 8-100 karakter, harf, rakam, - veya _.");
    }
    // RFC 7240: istemci "beklemeden cevap ver" diyorsa ve 2D ise 202 Accepted döneriz.
    const async = /(^|[\s,;])respond-async($|[\s,;])/i.test(prefer ?? "");
    const { view, replayed, accepted } = await this.payments.start(idempotencyKey, traceIdFrom(traceparent), request, { async });
    if (accepted) {
      response.status(HttpStatus.ACCEPTED)
        .setHeader("Preference-Applied", "respond-async")
        .setHeader("Location", `/payments/${view.attemptId}`);
      if (replayed) {
        response.setHeader("Idempotent-Replayed", "true");
      }
    } else if (replayed) {
      response.status(HttpStatus.OK).setHeader("Idempotent-Replayed", "true");
    } else {
      response.status(HttpStatus.CREATED).setHeader("Location", `/payments/${view.attemptId}`);
    }
    return view;
  }

  @Get("payments/:attemptId")
  @ApiOperation({ summary: "Denemenin güncel durumu" })
  get(@Param("attemptId", ParseUUIDPipe) attemptId: string): Promise<PaymentAttemptView> {
    return this.payments.get(attemptId);
  }

  @Post("payments/:attemptId/threeds-complete")
  @HttpCode(HttpStatus.OK)
  @ApiOperation({ summary: "3D Secure dönüşünü işle", description: "İmza ve eşleşme kontrol edilir; aynı dönüş yalnızca bir kez işlenir." })
  completeThreeDS(
    @Param("attemptId", ParseUUIDPipe) attemptId: string,
    @Body() callback: ThreeDSCallback,
  ): Promise<PaymentAttemptView> {
    return this.payments.completeThreeDS(attemptId, callback);
  }

  @Post("payments/:attemptId/verify")
  @HttpCode(HttpStatus.OK)
  @ApiOperation({ summary: "Sonucu belirsiz denemeyi iyzico'ya sor" })
  verify(@Param("attemptId", ParseUUIDPipe) attemptId: string): Promise<PaymentAttemptView> {
    return this.payments.verify(attemptId);
  }

  @Post("payments/:attemptId/refund")
  @HttpCode(HttpStatus.OK)
  @ApiOperation({ summary: "Tamamını iade et" })
  refund(@Param("attemptId", ParseUUIDPipe) attemptId: string, @Body() request: RefundRequest): Promise<PaymentAttemptView> {
    return this.payments.refund(attemptId, request.clientIp);
  }

  @Post("legacy-refunds")
  @HttpCode(HttpStatus.NO_CONTENT)
  @ApiOperation({
    summary: "Bu servisten önce alınmış bir ödemeyi iade et",
    description: "Ödeme denemesi kaydı olmayan eski siparişler için. iyzico ödeme numarası zorunlu.",
  })
  async refundLegacy(@Body() request: LegacyRefundRequest): Promise<void> {
    await this.payments.refundLegacy(request.iyzicoPaymentId, request.amount, request.clientIp, request.orderId);
  }
}
