package com.sarikaya.e_ticaret.payment.alerts;

import com.sarikaya.e_ticaret.exception.NotRefundableException;
import com.sarikaya.e_ticaret.payment.client.IyzicoServiceClient;
import com.sarikaya.e_ticaret.payment.events.PaymentEvent;
import com.sarikaya.e_ticaret.payment.events.PaymentEventException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fazladan alınan ödemeyi (ikinci ödeme, geç ödeme, siparişi olmayan ödeme) otomatik iade eder
 * ve admin'e uyarı yazar. Uyarı, olayın veritabanı işlemiyle birlikte yazılır.
 */
@Component
public class ExtraPaymentRefunder {
    private static final Logger log = LoggerFactory.getLogger(ExtraPaymentRefunder.class);

    private final IyzicoServiceClient iyzicoService;
    private final PaymentAlertRepository alerts;
    private final String serverIp;

    public ExtraPaymentRefunder(IyzicoServiceClient iyzicoService, PaymentAlertRepository alerts,
                                @Value("${payment.refund-ip:127.0.0.1}") String serverIp) {
        this.iyzicoService = iyzicoService;
        this.alerts = alerts;
        this.serverIp = serverIp;
    }

    public void refund(PaymentEvent.Data payment, PaymentAlert.Kind kind, String why) {
        if (alerts.existsByAttemptId(payment.attemptId())) {
            log.info("Deneme {} için uyarı zaten var; tekrar iade edilmez", payment.attemptId());
            return;
        }
        if (payment.iyzicoPaymentId() == null) {
            // iyzico numarası olmadan iade edilemez: bir insanın bakması gerekir.
            throw PaymentEventException.deadLetter(why + " iyzico ödeme numarası yok, otomatik iade edilemedi.");
        }

        boolean refunded;
        String message;
        try {
            iyzicoService.refund(payment.attemptId(), serverIp);
            refunded = true;
            message = why + " Otomatik iade edildi.";
        } catch (NotRefundableException alreadyClosed) {
            // Deneme zaten iade edilmiş ya da para çekilmemiş çıktı: iade gerekmiyor ama admin bilsin.
            refunded = false;
            message = why + " İade gerekmedi: " + alreadyClosed.getMessage();
        } catch (RuntimeException failure) {
            // iyzico/servis şu an cevap vermiyor: olay tekrar denenir (sonunda DLQ'ya düşer).
            throw PaymentEventException.retryLater(why + " İade şu an yapılamadı: " + failure.getMessage());
        }

        PaymentAlert alert = new PaymentAlert();
        alert.setKind(kind);
        alert.setOrderId(payment.orderId());
        alert.setAttemptId(payment.attemptId());
        alert.setIyzicoPaymentId(payment.iyzicoPaymentId());
        alert.setAmount(payment.amount());
        alert.setRefunded(refunded);
        alert.setMessage(message.length() <= 500 ? message : message.substring(0, 500));
        alerts.save(alert);
        log.warn("Ödeme uyarısı ({}): sipariş {}, deneme {}: {}", kind, payment.orderId(), payment.attemptId(), message);
    }
}
