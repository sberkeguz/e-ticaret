package com.sarikaya.e_ticaret.payment.controller;

import com.sarikaya.e_ticaret.payment.client.IyzicoServiceClient;
import com.sarikaya.e_ticaret.payment.dto.CheckoutRequest;
import com.sarikaya.e_ticaret.payment.dto.CheckoutResponse;
import com.sarikaya.e_ticaret.payment.dto.PayOrderRequest;
import com.sarikaya.e_ticaret.payment.dto.PaymentStatusResponse;
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
import java.util.Set;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
    private static final Set<String> RESULTS = Set.of("success", "review", "failed", "declined", "pending", "error");

    private final PaymentService paymentService;
    private final PaymentStatusLongPoll statusLongPoll;
    private final String frontendUrl;

    public PaymentController(
            PaymentService paymentService,
            PaymentStatusLongPoll statusLongPoll,
            @Value("${app.frontend-url:http://localhost:3000}") String frontendUrl) {
        this.paymentService = paymentService;
        this.statusLongPoll = statusLongPoll;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
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

    /**
     * "Ödemeniz işleniyor..." ekranı sonucu buradan sorar. waitMs verilirse (en fazla 15 sn) ödeme
     * işlenirken cevap bekletilir ve sonuç gelir gelmez döner; verilmezse hemen o anki durum döner.
     */
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

    /**
     * iyzico 3D Secure dönüşü (tarayıcı üzerinden form POST'u). Bu sayfa ödeme ekranındaki iframe
     * içinde, ngrok gibi herkese açık bir adresten açılır. Tarayıcılar böyle bir sayfanın iframe'de
     * localhost'a yönlenmesini engellediği için yönlendirme yerine sonucu postMessage ile ödeme
     * ekranına bildiriyoruz. Sayfa iframe dışında açılırsa doğrudan Siparişlerim'e gider.
     */
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
                  if (window.parent && window.parent !== window) {
                    window.parent.postMessage(message, "%3$s");
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
}
