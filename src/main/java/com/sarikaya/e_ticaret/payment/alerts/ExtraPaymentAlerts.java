package com.sarikaya.e_ticaret.payment.alerts;

import com.sarikaya.e_ticaret.payment.events.PaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fazladan alınan ödemeyi (ikinci ödeme, geç ödeme, siparişi olmayan ödeme) admin'e uyarı olarak
 * yazar. Para otomatik iade EDİLMEZ: admin "Ödeme uyarıları" kartından inceleyip karar verir.
 * Uyarı, olayın veritabanı işlemiyle birlikte yazılır; aynı deneme için ikinci uyarı yazılmaz.
 */
@Component
public class ExtraPaymentAlerts {
    private static final Logger log = LoggerFactory.getLogger(ExtraPaymentAlerts.class);

    private final PaymentAlertRepository alerts;

    public ExtraPaymentAlerts(PaymentAlertRepository alerts) {
        this.alerts = alerts;
    }

    public void record(PaymentEvent.Data payment, PaymentAlert.Kind kind, String why) {
        if (alerts.existsByAttemptId(payment.attemptId())) {
            log.info("Deneme {} için uyarı zaten var", payment.attemptId());
            return;
        }
        String message = why + " Para iade edilmedi; kontrol edin.";
        PaymentAlert alert = new PaymentAlert();
        alert.setKind(kind);
        alert.setOrderId(payment.orderId());
        alert.setAttemptId(payment.attemptId());
        alert.setIyzicoPaymentId(payment.iyzicoPaymentId());
        alert.setAmount(payment.amount());
        alert.setRefunded(false);
        alert.setMessage(message.length() <= 500 ? message : message.substring(0, 500));
        alerts.save(alert);
        log.warn("Ödeme uyarısı ({}): sipariş {}, deneme {}: {}", kind, payment.orderId(), payment.attemptId(), message);
    }
}
