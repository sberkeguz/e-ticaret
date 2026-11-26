package com.sarikaya.e_ticaret.payment.repository;

import com.sarikaya.e_ticaret.payment.entity.Payment;
import com.sarikaya.e_ticaret.payment.entity.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrder_Id(Long orderId);

    boolean existsByOrder_User_IdAndStatusIn(Long userId, List<PaymentStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.token = :token")
    Optional<Payment> findByTokenForUpdate(@Param("token") String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.attemptId = :attemptId")
    Optional<Payment> findByAttemptIdForUpdate(@Param("attemptId") UUID attemptId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.order.id = :orderId")
    Optional<Payment> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Payment> findTop100ByStatusAndReservationStartedAtBeforeOrderByReservationStartedAtAsc(
            PaymentStatus status, Instant reservationStartedAt);

    /** Kuyrukla giden 2D isteği sonuç beklenen (ödemesi işlenen) siparişler. */
    @Query("select p.order.id from Payment p where p.order.id in :orderIds "
            + "and p.status = com.sarikaya.e_ticaret.payment.entity.PaymentStatus.PENDING "
            + "and p.lastAttemptStatus = 'REQUESTED'")
    List<Long> findOrderIdsWithChargeInFlight(@Param("orderIds") java.util.Collection<Long> orderIds);

    /** Verilen siparişlerden ödemesi bu durumda olanlar (listelerde toplu sorgu). */
    @Query("select p.order.id from Payment p where p.order.id in :orderIds and p.status = :status")
    List<Long> findOrderIdsWithStatus(@Param("orderIds") java.util.Collection<Long> orderIds,
                                      @Param("status") PaymentStatus status);
}
