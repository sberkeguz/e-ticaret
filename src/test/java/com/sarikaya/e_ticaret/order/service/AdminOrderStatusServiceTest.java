package com.sarikaya.e_ticaret.order.service;

import com.sarikaya.e_ticaret.payment.entity.PaymentStatus;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.exception.BadRequestException;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.entity.Payment;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminOrderStatusServiceTest {

    private final OrderService orderService = mock(OrderService.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);

    private final AdminOrderStatusService service =
            new AdminOrderStatusService(orderService, paymentService, orderRepository, paymentRepository);

    private Order order;

    @BeforeEach
    void setUp() {
        order = new Order();
        order.setId(10L);
        order.setStatus(OrderStatus.PREPARING);
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
    }

    @Test
    void cancellingGoesThroughRefundBeforeStatusChange() {
        service.changeStatus(10L, "CANCELLED", "1.2.3.4");

        verify(paymentService).cancelByAdmin(10L, "1.2.3.4");
        verify(orderService).updateStatus(10L, OrderStatus.CANCELLED);
    }

    @Test
    void cancelledOrderCannotBeReopened() {
        order.setStatus(OrderStatus.CANCELLED);

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.changeStatus(10L, "PREPARING", "1.2.3.4"));

        assertEquals("İptal edilen sipariş yeniden açılamaz.", error.getMessage());
        verify(orderService, never()).updateStatus(any(), any());
    }

    @Test
    void unpaidOrderCannotBeShipped() {
        when(paymentRepository.findByOrder_Id(10L)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));

        assertThrows(BadRequestException.class, () -> service.changeStatus(10L, "SHIPPED", "1.2.3.4"));

        verify(orderService, never()).updateStatus(any(), any());
    }

    @Test
    void paidOrderCanBeShipped() {
        when(paymentRepository.findByOrder_Id(10L)).thenReturn(Optional.of(payment(PaymentStatus.PAID)));

        service.changeStatus(10L, "SHIPPED", "1.2.3.4");

        verify(orderService).updateStatus(10L, OrderStatus.SHIPPED);
        verify(paymentService, never()).cancelByAdmin(any(), anyString());
    }

    @Test
    void orderWithoutPaymentRecordCanBeShipped() {
        when(paymentRepository.findByOrder_Id(10L)).thenReturn(Optional.empty());

        service.changeStatus(10L, "SHIPPED", "1.2.3.4");

        verify(orderService).updateStatus(10L, OrderStatus.SHIPPED);
    }

    @Test
    void unknownOrOldTurkishStatusIsRejected() {
        // Durum artık kodla gelir; eski Türkçe yazı veya admin'in seçemeyeceği bir kod reddedilir.
        assertThrows(BadRequestException.class, () -> service.changeStatus(10L, "Kargoya verildi", "1.2.3.4"));
        assertThrows(BadRequestException.class, () -> service.changeStatus(10L, "PAYMENT_PENDING", "1.2.3.4"));
        assertThrows(BadRequestException.class, () -> service.changeStatus(10L, null, "1.2.3.4"));

        verify(orderService, never()).updateStatus(any(), any());
    }

    private Payment payment(PaymentStatus status) {
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setStatus(status);
        return payment;
    }
}
