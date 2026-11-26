package com.sarikaya.e_ticaret.order.service;

import com.sarikaya.e_ticaret.exception.BadRequestException;
import com.sarikaya.e_ticaret.exception.ResourceNotFoundException;
import com.sarikaya.e_ticaret.order.dto.OrderResponse;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.payment.entity.Payment;
import com.sarikaya.e_ticaret.payment.entity.PaymentStatus;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.payment.service.PaymentService;
import com.sarikaya.e_ticaret.shipment.entity.OrderShipment;
import com.sarikaya.e_ticaret.shipment.entity.ShipmentStatus;
import com.sarikaya.e_ticaret.shipment.exception.ConflictException;
import com.sarikaya.e_ticaret.shipment.repository.OrderShipmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

@Service
public class AdminOrderStatusService {

    private static final OrderStatus ORDER_CANCELLED = OrderStatus.CANCELLED;
    private static final PaymentStatus PAYMENT_PAID = PaymentStatus.PAID;
    /** Mal depodan çıkmış: kargoda ya da teslim edilmiş. */
    private static final Set<OrderStatus> OUT_FOR_DELIVERY = Set.of(OrderStatus.SHIPPED, OrderStatus.DELIVERED);

    private final OrderService orderService;
    private final PaymentService paymentService;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final OrderShipmentRepository shipmentRepository;

    public AdminOrderStatusService(OrderService orderService,
                                   PaymentService paymentService,
                                   OrderRepository orderRepository,
                                   PaymentRepository paymentRepository,
                                   OrderShipmentRepository shipmentRepository) {
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.shipmentRepository = shipmentRepository;
    }

    /**
     * Admin'in sipariş durumu değişikliği. Tek işlemde çalışır ve önce ödeme satırını, sonra sipariş
     * satırını, en son gönderi satırını kilitler (projedeki kilit sırası her yerde ödeme → sipariş →
     * gönderi: PaymentService.cancelByAdmin, PaymentEventHandler, kargo işlemleri de bu sırayla kilitler). Böylece aynı siparişe gelen eşzamanlı admin
     * istekleri ve ödeme olayları sırayla işlenir; ikinci istek ilkinin sonucunu görerek karar verir.
     *
     * Kurallar:
     *  - Siparişin FAILED olmayan bir gönderisi varsa (kargo istendi/yolda/teslim edildi) durumu elle
     *    değiştirilemez: aynı durumu yeniden seçmek dışında her istek 409 ORDER_HAS_SHIPMENT. Böylece
     *    kargoya istenmiş siparişin iptali (para iadesi + stok) kilit altında engellenir. Gönderi FAILED ise
     *    (koli hiç çıkmadı) iptal serbesttir, elle SHIPPED/DELIVERED yine 409'dur. Gönderi yoksa aşağıdaki
     *    kurallar aynen geçerlidir.
     *  - İptal edilmiş sipariş yeniden açılamaz.
     *  - Kargoya verilmiş (SHIPPED) ya da teslim edilmiş (DELIVERED) sipariş iptal edilemez; iptal parayı
     *    iade edip stoğu geri eklerdi.
     *  - PREPARING/SHIPPED/DELIVERED'a geçiş yalnızca ödeme PAID ise. Ödeme kaydı yoksa (ödeme akışından
     *    önceki eski siparişler) yalnızca zaten satılmış (OrderStatus.SOLD) bir sipariş ilerleyebilir.
     */
    @Transactional
    public OrderResponse changeStatus(Long orderId, String requestedStatus, String adminIp) {
        OrderStatus newStatus = OrderStatus.parse(requestedStatus);
        if (newStatus == null || !OrderStatus.ADMIN_SELECTABLE.contains(newStatus)) {
            throw new BadRequestException("Geçersiz sipariş durumu.");
        }
        // Kilit sırası: önce ödeme, sonra sipariş (deadlock olmasın diye; bkz. sınıf belgesi).
        Optional<Payment> payment = paymentRepository.findByOrderIdForUpdate(orderId);
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Sipariş bulunamadı."));
        Optional<OrderShipment> shipment = shipmentRepository.findByOrderIdForUpdate(orderId);
        shipment.ifPresent(found -> requireChangeAllowedWithShipment(found, order.getStatus(), newStatus));

        boolean orderIsAlreadyCancelled = ORDER_CANCELLED.equals(order.getStatus());
        boolean adminWantsToCancel = ORDER_CANCELLED.equals(newStatus);

        if (orderIsAlreadyCancelled && !adminWantsToCancel) {
            throw new BadRequestException("İptal edilen sipariş yeniden açılamaz.");
        }

        if (adminWantsToCancel) {
            if (OUT_FOR_DELIVERY.contains(order.getStatus())) {
                throw new BadRequestException("Kargoya verilmiş sipariş iptal edilemez.");
            }
            // Aynı işlemde çalışır; ödeme ve sipariş satırları bu işlemde zaten kilitli.
            paymentService.cancelByAdmin(orderId, adminIp);
            return orderService.updateStatus(orderId, newStatus);
        }

        PaymentStatus paymentStatus = payment.map(Payment::getStatus).orElse(null);
        boolean allowed = paymentStatus == null
                ? OrderStatus.SOLD.contains(order.getStatus())
                : PAYMENT_PAID.equals(paymentStatus);

        if (!allowed) {
            throw new BadRequestException("Ödemesi alınmamış sipariş hazırlanamaz veya kargoya verilemez.");
        }

        return orderService.updateStatus(orderId, newStatus);
    }

    private static void requireChangeAllowedWithShipment(OrderShipment shipment, OrderStatus current, OrderStatus requested) {
        boolean failed = shipment.getStatus() == ShipmentStatus.FAILED;
        boolean allowed = failed
                ? !OUT_FOR_DELIVERY.contains(requested)
                : requested == current;
        if (!allowed) {
            throw new ConflictException("ORDER_HAS_SHIPMENT",
                    "Bu siparişin kargo kaydı var; durumu kargo firmasından güncellenir.");
        }
    }
}
