package com.sarikaya.e_ticaret.payment.controller;

import com.sarikaya.e_ticaret.payment.client.IyzicoServiceClient;
import com.sarikaya.e_ticaret.payment.dto.CheckoutRequest;
import com.sarikaya.e_ticaret.payment.dto.CheckoutResponse;
import com.sarikaya.e_ticaret.payment.dto.PayOrderRequest;
import com.sarikaya.e_ticaret.payment.dto.PaymentStatusResponse;
import com.sarikaya.e_ticaret.payment.events.PaymentBroker;
import com.sarikaya.e_ticaret.payment.service.PaymentService;
import com.sarikaya.e_ticaret.payment.service.PaymentStatusLongPoll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
    private static final Set<String> RESULTS = Set.of("success", "review", "failed", "declined", "pending", "error");

    private final PaymentService paymentService;
    private final PaymentStatusLongPoll statusLongPoll;
    private final PaymentBroker broker;
    private final String frontendUrl;

    public PaymentController(
            PaymentService paymentService,
            PaymentStatusLongPoll statusLongPoll,
            PaymentBroker broker,
            @Value("${app.frontend-url:http://localhost:3000}") String frontendUrl) {
        this.paymentService = paymentService;
        this.statusLongPoll = statusLongPoll;
        this.broker = broker;
        this.frontendUrl = withoutTrailingSlashes(frontendUrl);
    }

    /** Mağaza ödeme ekranında "ödemeler geçici olarak alınamıyor" bandını göstermek için. */
    @GetMapping("/availability")
    public PaymentAvailability availability() {
        return new PaymentAvailability(broker.isUp(), broker.downSince());
    }

    public record PaymentAvailability(boolean available, Instant unavailableSince) {
    }

    @PostMapping("/checkout")
    public CheckoutResponse checkout(
            Authentication auth,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request,
            HttpServletRequest servletRequest) {
        return paymentService.initialize(auth.getName(), request, servletRequest.getRemoteAddr(), idempotencyKey);
    }

    @PostMapping("/orders/{orderId}/pay")
    public CheckoutResponse payOrder(
            Authentication auth,
            @PathVariable Long orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PayOrderRequest request,
            HttpServletRequest servletRequest) {
        return paymentService.payExistingOrder(auth.getName(), orderId, request, servletRequest.getRemoteAddr(),
                idempotencyKey);
    }

    @GetMapping("/orders/{orderId}/status")
    public DeferredResult<PaymentStatusResponse> status(Authentication auth, @PathVariable Long orderId,
                                                        @RequestParam(defaultValue = "0") long waitMs) {
        return statusLongPoll.status(auth.getName(), orderId, waitMs);
    }

    @PostMapping("/orders/{orderId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelOrder(Authentication auth, @PathVariable Long orderId) {
        paymentService.cancelPendingOrder(auth.getName(), orderId);
    }

    @PostMapping("/callback")
    public void callback(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String paymentId,
            @RequestParam(required = false) String conversationData,
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) String mdStatus,
            @RequestParam(required = false) String signature,
            HttpServletResponse response) throws IOException {
        String result;
        try {
            result = paymentService.completeThreeDS(new IyzicoServiceClient.ThreeDSCallback(
                    status, paymentId, conversationData, conversationId, mdStatus, signature));
        } catch (RuntimeException e) {
            log.warn("3D Secure dönüşü işlenemedi: {}", e.getMessage());
            result = "error";
        }
        writeResultPage(response, result);
    }

    /**
     * iyzico Ödeme Formu dönüşü: iyzico tarayıcıyı buraya POST ettirir (adreste deneme numarası,
     * gövdede formun token'ı). Sonuç sayfası, 3D dönüşündeki gibi ödeme ekranına haber verir.
     */
    @PostMapping("/form-callback")
    public void formCallback(@RequestParam(required = false) String attempt,
                             @RequestParam(required = false) String token,
                             HttpServletResponse response) throws IOException {
        String result;
        try {
            result = paymentService.completeCheckoutForm(attempt, token);
        } catch (RuntimeException e) {
            log.warn("Ödeme formu dönüşü işlenemedi: {}", e.getMessage());
            result = "error";
        }
        writeResultPage(response, result);
    }

    private void writeResultPage(HttpServletResponse response, String result) throws IOException {
        if (!RESULTS.contains(result)) {
            result = "pending";
        }
        String ordersUrl = frontendUrl + "/orders?payment=" + result;
        String origin = frontendOrigin();

        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getOutputStream().write(("""
                <!doctype html>
                <html lang="tr"><head><meta charset="utf-8"><title>Ödeme sonucu</title></head>
                <body style="font-family:sans-serif;padding:24px">
                <p>Ödeme sonucu işleniyor…</p>
                <p><a href="%1$s" target="_top">Siparişlerime dön</a></p>
                <script>
                (function () {
                  var message = { type: "iyzico-payment-result", result: "%2$s" };
                  // Ödeme ekranındaki çerçevede (veya iyzico formunun içindeki bir alt çerçevede) açıldıysa
                  // sonucu üst pencerelere bildir; tek başına açıldıysa siparişlere git.
                  var targets = [];
                  if (window.parent && window.parent !== window) targets.push(window.parent);
                  if (window.top && window.top !== window && window.top !== window.parent) targets.push(window.top);
                  if (targets.length) {
                    targets.forEach(function (target) { try { target.postMessage(message, "%3$s"); } catch (e) {} });
                  } else {
                    window.location.replace("%1$s");
                  }
                })();
                </script>
                </body></html>
                """.formatted(ordersUrl, result, origin)).getBytes(StandardCharsets.UTF_8));
    }

    private String frontendOrigin() {
        URI uri = URI.create(frontendUrl);
        String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + port;
    }

    private static String withoutTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
