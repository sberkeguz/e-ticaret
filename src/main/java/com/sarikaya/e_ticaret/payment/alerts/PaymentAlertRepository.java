package com.sarikaya.e_ticaret.payment.alerts;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaymentAlertRepository extends JpaRepository<PaymentAlert, Long> {
    List<PaymentAlert> findTop50ByResolvedAtIsNullOrderByCreatedAtDesc();

    long countByResolvedAtIsNull();

    List<PaymentAlert> findTop50ByOrderByCreatedAtDesc();

    boolean existsByAttemptId(UUID attemptId);
}
