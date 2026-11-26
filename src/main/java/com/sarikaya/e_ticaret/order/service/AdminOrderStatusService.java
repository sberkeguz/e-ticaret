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

    public AdminOrderStatusService(OrderService orderService,
                                   PaymentService paymentService,
                                   OrderRepository orderRepository,
                                   PaymentRepository paymentRepository) {
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
    }

    /**
     * Admin'in sipariş durumu değişikliği. Tek işlemde çalışır ve önce ödeme satırını, sonra sipariş
     * satırını kilitler (projedeki kilit sırası her yerde ödeme → sipariş: PaymentService.cancelByAdmin
     * ve PaymentEventHandler da önce ödemeyi kilitler). Böylece aynı siparişe gelen eşzamanlı admin
     * istekleri ve ödeme olayları sırayla işlenir; ikinci istek ilkinin sonucunu görerek karar verir.
     *
     * Kurallar:
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
}
