package com.sarikaya.e_ticaret.order.service;

import com.sarikaya.e_ticaret.exception.BadRequestException;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.payment.service.PaymentService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * K1: "ödeme kaydı olmadan PREPARING sipariş üretecek yol kalmamalı". AdminOrderStatusService,
 * eski POST /api/orders siparişleri için "ödeme kaydı yoksa serbest" istisnasını hâlâ taşıyor.
 * Bu istisna, sipariş henüz ödenmemiş bir durumdayken (PAYMENT_PENDING / PAYMENT_REVIEW /
 * PAYMENT_EXPIRED; yeni varsayılan da PAYMENT_PENDING) adminin onu PREPARING/SHIPPED/DELIVERED yapmasına izin veriyor.
 */
class AdminOrderStatusWithoutPaymentTest {

    private final OrderService orderService = mock(OrderService.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final AdminOrderStatusService service = new AdminOrderStatusService(
            orderService, mock(PaymentService.class), orderRepository, paymentRepository,
            mock(com.sarikaya.e_ticaret.shipment.repository.OrderShipmentRepository.class));

    /** KIRMIZI: ödenmemiş durumdaki, ödeme kaydı olmayan sipariş admin eliyle "satılmış" duruma geçebiliyor. */
    @ParameterizedTest(name = "{0} -> {1} reddedilmeli")
    @CsvSource({
            "PAYMENT_PENDING, PREPARING",
            "PAYMENT_PENDING, SHIPPED",
            "PAYMENT_REVIEW,  PREPARING",
            "PAYMENT_EXPIRED, PREPARING",
            "PAYMENT_EXPIRED, DELIVERED",
    })
    void unpaidOrderWithoutPaymentRecordCannotBecomeSold(OrderStatus current, String requested) {
        givenOrderWithoutPayment(current);

        assertThrows(BadRequestException.class, () -> service.changeStatus(10L, requested, "1.2.3.4"));

        verify(orderService, never()).updateStatus(any(), any());
    }

    /** Yeşil koruma: eski POST ucuyla açılmış (ödeme kaydı yok, zaten satılmış) siparişler ilerleyebilmeli. */
    @ParameterizedTest(name = "eski {0} siparişi ilerleyebilir")
    @EnumSource(value = OrderStatus.class, names = {"PREPARING", "SHIPPED"})
    void legacySoldOrderWithoutPaymentRecordCanStillMoveOn(OrderStatus current) {
        givenOrderWithoutPayment(current);

        service.changeStatus(10L, "DELIVERED", "1.2.3.4");

        verify(orderService).updateStatus(10L, OrderStatus.DELIVERED);
    }

    private void givenOrderWithoutPayment(OrderStatus status) {
        Order order = new Order();
        order.setId(10L);
        order.setStatus(status);
        when(orderRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderIdForUpdate(10L)).thenReturn(Optional.empty());
    }
}
