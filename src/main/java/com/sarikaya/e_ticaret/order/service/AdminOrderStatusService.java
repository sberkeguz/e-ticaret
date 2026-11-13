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

@Service
public class AdminOrderStatusService {

    private static final OrderStatus ORDER_CANCELLED = OrderStatus.CANCELLED;
    private static final PaymentStatus PAYMENT_PAID = PaymentStatus.PAID;

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

    @Transactional
    public OrderResponse changeStatus(Long orderId, String requestedStatus, String adminIp) {
        OrderStatus newStatus = OrderStatus.parse(requestedStatus);
        if (newStatus == null || !OrderStatus.ADMIN_SELECTABLE.contains(newStatus)) {
            throw new BadRequestException("Geçersiz sipariş durumu.");
        }
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Sipariş bulunamadı."));

        boolean orderIsAlreadyCancelled = ORDER_CANCELLED.equals(order.getStatus());
        boolean adminWantsToCancel = ORDER_CANCELLED.equals(newStatus);

        if (orderIsAlreadyCancelled && !adminWantsToCancel) {
            throw new BadRequestException("İptal edilen sipariş yeniden açılamaz.");
        }

        if (adminWantsToCancel) {
            paymentService.cancelByAdmin(orderId, adminIp);
            return orderService.updateStatus(orderId, newStatus);
        }

        PaymentStatus paymentStatus = paymentRepository.findByOrder_Id(orderId)
                .map(Payment::getStatus)
                .orElse(null);

        boolean orderHasPaymentRecord = paymentStatus != null;
        boolean paymentIsReceived = PAYMENT_PAID.equals(paymentStatus);

        if (orderHasPaymentRecord && !paymentIsReceived) {
            throw new BadRequestException("Ödemesi alınmamış sipariş hazırlanamaz veya kargoya verilemez.");
        }

        return orderService.updateStatus(orderId, newStatus);
    }
}
