package com.sarikaya.e_ticaret.shipment.repository;

import com.sarikaya.e_ticaret.shipment.entity.OrderShipment;
import com.sarikaya.e_ticaret.shipment.entity.ShipmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderShipmentRepository extends JpaRepository<OrderShipment, Long> {
    Optional<OrderShipment> findByOrderId(Long orderId);

    /**
     * Gönderiyi satır kilidiyle okur. Kilit sırası her yerde ödeme → sipariş → gönderidir: önce
     * PaymentRepository.findByOrderIdForUpdate, sonra OrderRepository.findByIdForUpdate çağrılmalıdır.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from OrderShipment s where s.orderId = :orderId")
    Optional<OrderShipment> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    /** Listelerde toplu yükleme (sipariş başına ayrı sorgu yok). */
    List<OrderShipment> findAllByOrderIdIn(Collection<Long> orderIds);

    long countByStatusAndStatusAtBefore(ShipmentStatus status, Instant before);
}
