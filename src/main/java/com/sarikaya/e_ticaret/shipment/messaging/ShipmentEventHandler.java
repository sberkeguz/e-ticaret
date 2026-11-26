package com.sarikaya.e_ticaret.shipment.messaging;

import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.events.ProcessedMessages;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.shipment.entity.OrderShipment;
import com.sarikaya.e_ticaret.shipment.entity.ShipmentStatus;
import com.sarikaya.e_ticaret.shipment.repository.OrderShipmentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

/**
 * cargo-service gönderi olayını tek işlemde uygular.
 *  1. messageId processed_messages'a yazılır; zaten varsa olay atlanır. İşlem geri alınırsa (DLQ/tekrar)
 *     bu kayıt da geri alınır.
 *  2. Kilit sırası ödeme → sipariş → gönderi. Sipariş ya da gönderi kaydı yoksa → DLQ.
 *  3. requestNo: olaydaki kayıttakinden küçükse (önceki denemenin geciken olayı) atlanır; büyükse
 *     (backend'in bilmediği istek) → DLQ. Eşitse ve sequence son uygulanandan büyük değilse atlanır.
 *  4. Olay anlık görüntü olarak uygulanır: durum, firma, takip no, statusAt, sequence. REJECTED →
 *     FAILED + PAYMENT_NOT_CONFIRMED, FAILED → FAILED + CARRIER_FAILED (firmanın metni saklanmaz).
 *     Bilinmeyen durum ya da sütuna sığmayan alan → DLQ.
 *  5. Sipariş: CREATED/IN_TRANSIT/DELIVERY_FAILED ve PREPARING → SHIPPED; DELIVERED ve PREPARING|SHIPPED →
 *     DELIVERED; FAILED siparişi değiştirmez. Sipariş beklenmeyen durumdaysa (ör. CANCELLED) gönderi yine
 *     kaydedilir, sipariş değişmez, hata loglanır ve shipment.events.order_conflicts sayacı artar.
 *     Otomatik iade yapılmaz.
 */
@Component
public class ShipmentEventHandler {
    private static final Logger log = LoggerFactory.getLogger(ShipmentEventHandler.class);
    private static final Set<OrderStatus> SHIPPABLE = Set.of(OrderStatus.PREPARING, OrderStatus.SHIPPED);

    private final ProcessedMessages processed;
    private final PaymentRepository payments;
    private final OrderRepository orders;
    private final OrderShipmentRepository shipments;
    private final Counter orderConflicts;

    public ShipmentEventHandler(ProcessedMessages processed, PaymentRepository payments, OrderRepository orders,
                                OrderShipmentRepository shipments, MeterRegistry registry) {
        this.processed = processed;
        this.payments = payments;
        this.orders = orders;
        this.shipments = shipments;
        this.orderConflicts = Counter.builder("shipment.events.order_conflicts")
                .description("Siparişin beklenmeyen durumdayken (ör. iptal) gelen kargo olayları")
                .register(registry);
    }

    @Transactional
    public void handle(String messageId, ShipmentEvent event) {
        String type = event.type() == null || event.type().isBlank() ? "ShipmentEvent" : event.type();
        if (!processed.markProcessed(messageId, type)) {
            log.info("Gönderi olayı {} daha önce işlenmiş, atlandı", messageId);
            return;
        }
        ShipmentEvent.Data data = event.data();
        long orderId = data.orderId();

        payments.findByOrderIdForUpdate(orderId);
        Order order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> ShipmentEventException.deadLetter("Gönderi olayındaki sipariş bulunamadı: " + orderId));
        OrderShipment shipment = shipments.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> ShipmentEventException.deadLetter("Siparişin gönderi kaydı yok: " + orderId));

        int requestNo = data.requestNo();
        if (requestNo < shipment.getRequestNo()) {
            log.info("Sipariş {} için eski kargo isteğinin ({}) olayı atlandı (güncel istek {})",
                    orderId, requestNo, shipment.getRequestNo());
            return;
        }
        if (requestNo > shipment.getRequestNo()) {
            throw ShipmentEventException.deadLetter("Olaydaki kargo isteği (" + requestNo
                    + ") backend'in bildiğinden (" + shipment.getRequestNo() + ") yeni; sipariş " + orderId);
        }
        if (event.sequence() <= shipment.getLastEventSequence()) {
            log.info("Sipariş {} için eski/tekrar gönderi olayı atlandı (sıra {} <= {})",
                    orderId, event.sequence(), shipment.getLastEventSequence());
            return;
        }

        apply(shipment, event);
        updateOrder(order, shipment.getStatus());
    }

    private static void apply(OrderShipment shipment, ShipmentEvent event) {
        ShipmentEvent.Data data = event.data();
        ShipmentStatus status;
        String failureCode = null;
        switch (data.status()) {
            case "CREATED" -> status = ShipmentStatus.CREATED;
            case "IN_TRANSIT" -> status = ShipmentStatus.IN_TRANSIT;
            case "DELIVERED" -> status = ShipmentStatus.DELIVERED;
            case "DELIVERY_FAILED" -> status = ShipmentStatus.DELIVERY_FAILED;
            case "FAILED" -> {
                status = ShipmentStatus.FAILED;
                failureCode = OrderShipment.CARRIER_FAILED;
            }
            case "REJECTED" -> {
                status = ShipmentStatus.FAILED;
                failureCode = OrderShipment.PAYMENT_NOT_CONFIRMED;
            }
            default -> throw ShipmentEventException.deadLetter("Beklenmeyen gönderi durumu: " + data.status());
        }
        String carrierCode = fit("carrierCode", data.carrierCode(), 50);
        String carrierName = fit("carrierName", data.carrierName(), 100);
        String trackingNumber = fit("trackingNumber", data.trackingNumber(), 100);

        shipment.setStatus(status);
        shipment.setFailureCode(failureCode);
        if (data.shipmentId() != null) {
            shipment.setCargoShipmentId(data.shipmentId());
        }
        shipment.setCarrierCode(carrierCode);
        shipment.setCarrierName(carrierName);
        shipment.setTrackingNumber(trackingNumber);
        shipment.setStatusAt(data.statusAt().toInstant());
        shipment.setLastEventSequence(event.sequence());
        shipment.setUpdatedAt(Instant.now());
    }

    private void updateOrder(Order order, ShipmentStatus shipmentStatus) {
        OrderStatus current = order.getStatus();
        switch (shipmentStatus) {
            case CREATED, IN_TRANSIT, DELIVERY_FAILED -> {
                if (current == OrderStatus.PREPARING) {
                    order.setStatus(OrderStatus.SHIPPED);
                } else if (current != OrderStatus.SHIPPED) {
                    conflict(order, shipmentStatus);
                }
            }
            case DELIVERED -> {
                if (SHIPPABLE.contains(current)) {
                    order.setStatus(OrderStatus.DELIVERED);
                } else if (current != OrderStatus.DELIVERED) {
                    conflict(order, shipmentStatus);
                }
            }
            default -> {
                // FAILED (ve REQUESTED): koli çıkmadı, sipariş olduğu gibi kalır.
            }
        }
    }

    private void conflict(Order order, ShipmentStatus shipmentStatus) {
        orderConflicts.increment();
        log.error("Sipariş {} {} durumundayken gönderi {} oldu; sipariş değiştirilmedi, elle kontrol gerekli",
                order.getId(), order.getStatus(), shipmentStatus);
    }

    private static String fit(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw ShipmentEventException.deadLetter("Gönderi olayında " + field + " çok uzun (" + value.length() + ")");
        }
        return value;
    }
}
