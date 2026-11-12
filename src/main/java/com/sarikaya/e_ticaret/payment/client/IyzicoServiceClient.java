package com.sarikaya.e_ticaret.payment.client;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.sarikaya.e_ticaret.exception.BadRequestException;
import com.sarikaya.e_ticaret.exception.NotRefundableException;
import com.sarikaya.e_ticaret.exception.PaymentServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * IyzicoService'e senkron HTTP istemcisi. Backend iyzico ile artık doğrudan konuşmaz.
 * Kart bilgisi yalnızca bu isteğin gövdesinde geçer; hiçbir yere yazılmaz.
 */
@Component
public class IyzicoServiceClient {
    private static final Logger log = LoggerFactory.getLogger(IyzicoServiceClient.class);

    private final RestClient http;

    public IyzicoServiceClient(
            @Value("${iyzico-service.url:http://localhost:8081}") String baseUrl,
            @Value("${iyzico-service.token:}") String token) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        // iyzico'nun kendi zaman aşımı 140 sn; servis cevabını beklerken ondan önce vazgeçmeyelim.
        requestFactory.setReadTimeout(Duration.ofSeconds(180));
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("X-Internal-Token", token)
                .build();
    }

    /**
     * Ödemeyi başlatır. async = true ise (yalnızca 2D) servis iyzico'yu beklemeden 202 ile döner;
     * sonuç RabbitMQ olayıyla gelir. Kart bilgisi yine bu senkron istekte gider, kuyruğa girmez.
     */
    public AttemptResult startPayment(String idempotencyKey, StartPayment request, boolean async) {
        return call(() -> http.post().uri("/payments")
                .header("Idempotency-Key", idempotencyKey)
                .headers(headers -> {
                    if (async) {
                        headers.add("Prefer", "respond-async");
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(AttemptResult.class));
    }

    public AttemptResult getAttempt(UUID attemptId) {
        return call(() -> http.get().uri("/payments/{id}", attemptId)
                .retrieve()
                .body(AttemptResult.class));
    }

    public AttemptResult completeThreeDS(UUID attemptId, ThreeDSCallback callback) {
        return call(() -> http.post().uri("/payments/{id}/threeds-complete", attemptId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(callback)
                .retrieve()
                .body(AttemptResult.class));
    }

    public AttemptResult refund(UUID attemptId, String clientIp) {
        return call(() -> http.post().uri("/payments/{id}/refund", attemptId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RefundBody(clientIp))
                .retrieve()
                .body(AttemptResult.class));
    }

    public void refundLegacy(String iyzicoPaymentId, BigDecimal amount, String clientIp, Long orderId) {
        call(() -> http.post().uri("/legacy-refunds")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new LegacyRefundBody(iyzicoPaymentId, amount, clientIp, orderId))
                .retrieve()
                .toBodilessEntity());
    }

    /** Servisin RFC 9457 hatalarını kullanıcıya gösterilecek mesajlara çevirir. */
    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException error) {
            ProblemDetail problem = error.getResponseBodyAs(ProblemDetail.class);
            String type = problem == null || problem.getType() == null ? "" : problem.getType().getPath();
            String detail = problem == null ? null : problem.getDetail();
            log.warn("IyzicoService hata döndü: {} {} {}", error.getStatusCode(), type, detail);
            throw switch (type) {
                case "/problems/payment-in-progress", "/problems/order-has-active-attempt" -> new BadRequestException(
                        "Bu sipariş için ödeme zaten işleniyor. Birkaç saniye sonra Siparişlerim sayfasını kontrol edin.");
                case "/problems/idempotency-key-reused" -> new BadRequestException(
                        "Ödeme isteği değişti. Sayfayı yenileyip tekrar deneyin.");
                case "/problems/not-refundable" -> new NotRefundableException(detail);
                case "/problems/provider-failed", "/problems/callback-rejected" -> new BadRequestException(detail);
                case "/problems/provider-not-configured" -> new BadRequestException("iyzico API anahtarları yapılandırılmamış.");
                default -> new PaymentServiceUnavailableException();
            };
        } catch (ResourceAccessException unreachable) {
            log.warn("IyzicoService'e ulaşılamadı: {}", unreachable.getMessage());
            throw new PaymentServiceUnavailableException();
        }
    }

    // ---------------------------------------------------------------- istek/cevap gövdeleri

    /** Tutarlar JSON'da metin olarak gider ("25.00"): karşı taraf float'a çevirmesin. */
    public record StartPayment(Long orderId, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount, boolean threeDS, String clientIp,
                               Buyer buyer, Address address, List<BasketItem> items, Card card) {
    }

    public record Buyer(String id, String firstName, String lastName, String email, String phone,
                        String identityNumber, String city, String address) {
    }

    public record Address(String contactName, String city, String address) {
    }

    public record BasketItem(String id, String name, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal price) {
    }

    public record Card(String holderName, String number, String expireMonth, String expireYear, String cvc) {
        @Override
        public String toString() {
            String last4 = number == null || number.length() < 4 ? "" : number.substring(number.length() - 4);
            return "Card[**** " + last4 + "]";
        }
    }

    public record ThreeDSCallback(String status, String paymentId, String conversationData,
                                  String conversationId, String mdStatus, String signature) {
    }

    /** status: CREATED | SENT | AWAITING_3DS | CONFIRM_SENT | SUCCEEDED | FAILED | VERIFYING | REFUNDED */
    public record AttemptResult(UUID attemptId, Long orderId, String status, String iyzicoPaymentId,
                                String failureReason, String traceId, String threeDSHtmlContent) {
    }

    private record RefundBody(String clientIp) {
    }

    private record LegacyRefundBody(String iyzicoPaymentId, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount, String clientIp, Long orderId) {
    }
}
