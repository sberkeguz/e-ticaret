import { HttpStatus } from "@nestjs/common";

/**
 * İstemciye RFC 9457 problem olarak dönen hata. `type` sabit ve makinece okunur;
 * backend kararlarını buna göre verir, `detail` insan içindir.
 */
export class ApiProblem extends Error {
  private constructor(
    readonly status: HttpStatus,
    readonly type: string,
    readonly title: string,
    detail: string,
  ) {
    super(detail);
  }

  static of(status: HttpStatus, type: string, title: string, detail: string): ApiProblem {
    return new ApiProblem(status, type, title, detail);
  }

  static idempotencyKeyReused(): ApiProblem {
    return new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-key-reused",
      "Idempotency-Key başka bir istekle kullanılmış",
      "Bu anahtar farklı içerikli bir istek için kullanıldı. Yeni ödeme denemesi için yeni anahtar gönderin.");
  }

  static paymentInProgress(): ApiProblem {
    return new ApiProblem(HttpStatus.CONFLICT, "payment-in-progress",
      "Ödeme işleniyor", "Bu anahtarla başlatılan ödeme hâlâ işleniyor.");
  }

  static orderHasActiveAttempt(orderId: number): ApiProblem {
    return new ApiProblem(HttpStatus.CONFLICT, "order-has-active-attempt",
      "Siparişin devam eden bir ödemesi var", `Sipariş ${orderId} için devam eden bir ödeme denemesi var.`);
  }

  static basketTotalMismatch(): ApiProblem {
    return new ApiProblem(HttpStatus.BAD_REQUEST, "basket-total-mismatch",
      "Sepet toplamı tutmuyor", "Kalemlerin toplamı ödeme tutarına eşit olmalı.");
  }

  static callbackRejected(detail: string): ApiProblem {
    return new ApiProblem(HttpStatus.BAD_REQUEST, "callback-rejected", "3D Secure dönüşü reddedildi", detail);
  }

  static notRefundable(detail: string): ApiProblem {
    return new ApiProblem(HttpStatus.CONFLICT, "not-refundable", "Bu ödeme iade edilemez", detail);
  }

  static providerFailed(detail: string): ApiProblem {
    return new ApiProblem(HttpStatus.BAD_GATEWAY, "provider-failed", "iyzico işlemi yapamadı", detail);
  }

  static notConfigured(): ApiProblem {
    return new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "provider-not-configured",
      "iyzico yapılandırılmamış", "iyzico API anahtarları tanımlı değil.");
  }

  static attemptNotFound(detail: string): ApiProblem {
    return new ApiProblem(HttpStatus.NOT_FOUND, "attempt-not-found", "Ödeme denemesi bulunamadı", detail);
  }

  static validation(detail: string): ApiProblem {
    return new ApiProblem(HttpStatus.BAD_REQUEST, "validation-error", "İstek doğrulanamadı", detail);
  }

  static unauthorized(): ApiProblem {
    return new ApiProblem(HttpStatus.UNAUTHORIZED, "unauthorized", "Yetkisiz", "Geçerli X-Internal-Token gerekli.");
  }
}
