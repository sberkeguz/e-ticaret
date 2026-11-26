package com.sarikaya.e_ticaret.shipment.service;

import com.sarikaya.e_ticaret.exception.ResourceNotFoundException;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.entity.Payment;
import com.sarikaya.e_ticaret.payment.entity.PaymentStatus;
import com.sarikaya.e_ticaret.payment.outbox.OutboxRelay;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.shipment.entity.OrderShipment;
import com.sarikaya.e_ticaret.shipment.entity.ShipmentStatus;
import com.sarikaya.e_ticaret.shipment.exception.ConflictException;
import com.sarikaya.e_ticaret.shipment.messaging.CargoTopology;
import com.sarikaya.e_ticaret.shipment.messaging.ShipmentRequested;
import com.sarikaya.e_ticaret.shipment.repository.OrderShipmentRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * "Kargoya ver" ve "Tekrar dene". Her biri tek işlemdir: gönderi satırı ve cargo-service komutu (outbox)
 * birlikte yazılır ya da hiçbiri yazılmaz. RabbitMQ kapalı olsa da istek kabul edilir; komut outbox'ta
 * bekler ve bağlantı gelince gider. Kilit sırası ödeme → sipariş → gönderi.
 * Uygunluk: sipariş PREPARING, ödeme PAID, alıcı bilgisi cargo-service'in kabul edeceği biçimde ve
 * siparişte en az bir ürün. Alıcı bilgisi loglanmaz.
 */
@Service
public class ShipmentService {
    private static final Logger log = LoggerFactory.getLogger(ShipmentService.class);
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");

    static final String EXISTS = "Bu sipariş zaten kargoya verildi.";
    static final String NOT_ALLOWED = "Sipariş kargoya verilemez: hazırlanıyor durumunda olmalı ve ödemesi alınmış olmalı.";
    static final String NOT_RETRYABLE = "Kargo isteği şu an tekrar denenemez; sayfayı yenileyip kargo durumunu kontrol edin.";
    static final String UNSAFE_RECIPIENT = "Alıcı adresinde geçersiz karakter var; adresi düzeltin.";
    static final String MISSING_RECIPIENT = "Siparişin teslimat bilgileri eksik; kargoya verilemez.";
    static final String INVALID_PARCEL = "Siparişte ürün ya da tutar bilgisi geçersiz; kargoya verilemez.";

    private final PaymentRepository payments;
    private final OrderRepository orders;
    private final OrderShipmentRepository shipments;
    private final OutboxRelay outbox;
    private final ObjectProvider<Tracer> tracer;

    public ShipmentService(PaymentRepository payments, OrderRepository orders, OrderShipmentRepository shipments,
                           OutboxRelay outbox, ObjectProvider<Tracer> tracer) {
        this.payments = payments;
        this.orders = orders;
        this.shipments = shipments;
        this.outbox = outbox;
        this.tracer = tracer;
    }

    /** İlk kargo isteği (requestNo 1). Gönderi zaten varsa 409 SHIPMENT_EXISTS. */
    @Transactional
    public void request(Long orderId) {
        Optional<Payment> payment = payments.findByOrderIdForUpdate(orderId);
        Order order = lockOrder(orderId);
        if (shipments.findByOrderIdForUpdate(orderId).isPresent()) {
            throw new ConflictException("SHIPMENT_EXISTS", EXISTS);
        }
        if (!eligible(order, payment)) {
            throw new ConflictException("SHIPMENT_NOT_ALLOWED", NOT_ALLOWED);
        }
        UUID messageId = UUID.randomUUID();
        ShipmentRequested command = command(messageId, order, 1, "SHIPMENT_NOT_ALLOWED");

        Instant now = Instant.now();
        OrderShipment shipment = new OrderShipment();
        shipment.setOrderId(orderId);
        shipment.setRequestNo(1);
        shipment.setStatus(ShipmentStatus.REQUESTED);
        shipment.setStatusAt(now);
        shipment.setCreatedAt(now);
        shipment.setUpdatedAt(now);
        try {
            shipments.saveAndFlush(shipment);
        } catch (DataIntegrityViolationException duplicate) {
            // Kilitler bunu önler; yine de order_id tekil kısıtı son güvencedir.
            throw new ConflictException("SHIPMENT_EXISTS", EXISTS);
        }
        outbox.enqueue(messageId, CargoTopology.EXCHANGE, CargoTopology.SHIPMENT_CREATE_ROUTING_KEY,
                ShipmentRequested.MESSAGE_TYPE, command);
        log.info("Sipariş {} için kargo isteği yazıldı (istek 1, mesaj {})", orderId, messageId);
    }

    /**
     * FAILED gönderi için yeni kargo isteği: requestNo bir artar, REQUESTED olur, yeni komut yazılır.
     * Gönderi FAILED değilse, requestNo beklenenden farklıysa (ör. çift tıklama) ya da sipariş artık
     * PREPARING + PAID değilse 409 SHIPMENT_NOT_RETRYABLE. Olay sırası (lastEventSequence) sıfırlanmaz.
     */
    @Transactional
    public void retry(Long orderId, int expectedRequestNo) {
        Optional<Payment> payment = payments.findByOrderIdForUpdate(orderId);
        Order order = lockOrder(orderId);
        OrderShipment shipment = shipments.findByOrderIdForUpdate(orderId)
                .filter(found -> found.getStatus() == ShipmentStatus.FAILED && found.getRequestNo() == expectedRequestNo)
                .orElseThrow(() -> new ConflictException("SHIPMENT_NOT_RETRYABLE", NOT_RETRYABLE));
        if (!eligible(order, payment)) {
            throw new ConflictException("SHIPMENT_NOT_RETRYABLE", NOT_RETRYABLE);
        }
        int requestNo = shipment.getRequestNo() + 1;
        UUID messageId = UUID.randomUUID();
        ShipmentRequested command = command(messageId, order, requestNo, "SHIPMENT_NOT_RETRYABLE");

        Instant now = Instant.now();
        shipment.setRequestNo(requestNo);
        shipment.setStatus(ShipmentStatus.REQUESTED);
        shipment.setFailureCode(null);
        shipment.setStatusAt(now);
        shipment.setUpdatedAt(now);
        outbox.enqueue(messageId, CargoTopology.EXCHANGE, CargoTopology.SHIPMENT_CREATE_ROUTING_KEY,
                ShipmentRequested.MESSAGE_TYPE, command);
        log.info("Sipariş {} için kargo isteği yeniden yazıldı (istek {}, mesaj {})", orderId, requestNo, messageId);
    }

    private Order lockOrder(Long orderId) {
        return orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Sipariş bulunamadı."));
    }

    private static boolean eligible(Order order, Optional<Payment> payment) {
        return order.getStatus() == OrderStatus.PREPARING
                && payment.map(Payment::getStatus).filter(PaymentStatus.PAID::equals).isPresent();
    }

    /** Komutu kurar; cargo-service'in reddedeceği alıcı/koli bilgisinde 409 (alan değeri söylenmez). */
    private ShipmentRequested command(UUID messageId, Order order, int requestNo, String conflictCode) {
        ShipmentRequested command = ShipmentRequested.forOrder(messageId, order, requestNo, currentTraceId(), Instant.now());
        ShipmentRequested.RecipientProblem problem = command.recipient().problem();
        if (problem == ShipmentRequested.RecipientProblem.UNSAFE_CHARACTER) {
            throw new ConflictException(conflictCode, UNSAFE_RECIPIENT);
        }
        if (problem == ShipmentRequested.RecipientProblem.MISSING) {
            throw new ConflictException(conflictCode, MISSING_RECIPIENT);
        }
        if (!command.parcel().valid()) {
            throw new ConflictException(conflictCode, INVALID_PARCEL);
        }
        return command;
    }

    /** İsteğin izleme numarası (W3C, 32 hex); yoksa null (cargo-service kendisi üretir). */
    private String currentTraceId() {
        Tracer current = tracer.getIfAvailable();
        Span span = current == null ? null : current.currentSpan();
        String traceId = span == null ? null : span.context().traceId();
        return traceId != null && TRACE_ID.matcher(traceId).matches() ? traceId : null;
    }
}
