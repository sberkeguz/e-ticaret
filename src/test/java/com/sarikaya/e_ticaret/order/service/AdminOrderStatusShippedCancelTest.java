package com.sarikaya.e_ticaret.order.service;

import com.sarikaya.e_ticaret.exception.BadRequestException;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.entity.Payment;
import com.sarikaya.e_ticaret.payment.entity.PaymentStatus;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.payment.service.PaymentService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BK1: kargoya verilmiş (SHIPPED) ya da teslim edilmiş (DELIVERED) sipariş admin eliyle iptal
 * edilemez. İptal, parayı iade edip stoğu geri eklerdi; oysa mal müşteride ya da yolda.
 */
class AdminOrderStatusShippedCancelTest {

    private final OrderService orderService = mock(OrderService.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final AdminOrderStatusService service =
            new AdminOrderStatusService(orderService, paymentService, orderRepository, paymentRepository);

    @ParameterizedTest(name = "{0} sipariş iptal edilemez")
    @EnumSource(value = OrderStatus.class, names = {"SHIPPED", "DELIVERED"})
    void shippedOrDeliveredOrderCannotBeCancelled(OrderStatus current) {
        Order order = new Order();
        order.setId(10L);
        order.setStatus(current);
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setStatus(PaymentStatus.PAID);
        when(orderRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderIdForUpdate(10L)).thenReturn(Optional.of(payment));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.changeStatus(10L, "CANCELLED", "1.2.3.4"));

        assertEquals("Kargoya verilmiş sipariş iptal edilemez.", error.getMessage());
        verify(paymentService, never()).cancelByAdmin(any(), anyString());
        verify(orderService, never()).updateStatus(any(), any());
        assertEquals(PaymentStatus.PAID, payment.getStatus());
        assertEquals(current, order.getStatus());
    }
}
