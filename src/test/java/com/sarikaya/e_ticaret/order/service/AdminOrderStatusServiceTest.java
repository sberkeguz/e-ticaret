package com.sarikaya.e_ticaret.order.service;

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
        order.setStatus("Hazırlanıyor");
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
    }

    @Test
    void cancellingGoesThroughRefundBeforeStatusChange() {
        service.changeStatus(10L, "İptal edildi", "1.2.3.4");

        verify(paymentService).cancelByAdmin(10L, "1.2.3.4");
        verify(orderService).updateStatus(10L, "İptal edildi");
    }

    @Test
    void cancelledOrderCannotBeReopened() {
        order.setStatus("İptal edildi");

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.changeStatus(10L, "Hazırlanıyor", "1.2.3.4"));

        assertEquals("İptal edilen sipariş yeniden açılamaz.", error.getMessage());
        verify(orderService, never()).updateStatus(any(), anyString());
    }

    @Test
    void unpaidOrderCannotBeShipped() {
        when(paymentRepository.findByOrder_Id(10L)).thenReturn(Optional.of(payment("PENDING")));

        assertThrows(BadRequestException.class, () -> service.changeStatus(10L, "Kargoya verildi", "1.2.3.4"));

        verify(orderService, never()).updateStatus(any(), anyString());
    }

    @Test
    void paidOrderCanBeShipped() {
        when(paymentRepository.findByOrder_Id(10L)).thenReturn(Optional.of(payment("PAID")));

        service.changeStatus(10L, "Kargoya verildi", "1.2.3.4");

        verify(orderService).updateStatus(10L, "Kargoya verildi");
        verify(paymentService, never()).cancelByAdmin(any(), anyString());
    }

    @Test
    void orderWithoutPaymentRecordCanBeShipped() {
        when(paymentRepository.findByOrder_Id(10L)).thenReturn(Optional.empty());

        service.changeStatus(10L, "Kargoya verildi", "1.2.3.4");

        verify(orderService).updateStatus(10L, "Kargoya verildi");
    }

    private Payment payment(String status) {
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setStatus(status);
        return payment;
    }
}
