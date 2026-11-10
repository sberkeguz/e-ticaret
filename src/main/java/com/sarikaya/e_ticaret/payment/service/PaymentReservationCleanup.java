package com.sarikaya.e_ticaret.payment.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentReservationCleanup {
    private final PaymentService paymentService;

    public PaymentReservationCleanup(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Scheduled(fixedDelayString = "${payment.reservation-cleanup-delay-ms:300000}")
    public void expireReservations() {
        paymentService.expireReservations();
    }
}
