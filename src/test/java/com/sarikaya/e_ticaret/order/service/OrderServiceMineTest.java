package com.sarikaya.e_ticaret.order.service;

import com.sarikaya.e_ticaret.order.dto.OrderResponse;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderServiceMineTest {

    @Test
    void marksOrdersWhosePaymentIsBeingProcessed() {
        OrderRepository orders = mock(OrderRepository.class);
        PaymentRepository payments = mock(PaymentRepository.class);
        User buyer = User.builder().username("buyer").build();
        Order processing = order(1L, buyer);
        Order waiting = order(2L, buyer);
        when(orders.findAllByUserUsernameOrderByCreatedAtDesc("buyer")).thenReturn(List.of(processing, waiting));
        when(payments.findOrderIdsWithChargeInFlight(List.of(1L, 2L))).thenReturn(List.of(1L));

        List<OrderResponse> mine = new OrderService(orders, mock(ProductRepository.class), mock(UserRepository.class), payments)
                .findMine("buyer");

        assertTrue(mine.get(0).paymentProcessing(), "isteği gönderilmiş sipariş: buton yerine 'işleniyor'");
        assertFalse(mine.get(1).paymentProcessing(), "reddedilmiş/beklemedeki sipariş: tekrar ödenebilir");
    }

    private static Order order(Long id, User user) {
        Order order = new Order();
        order.setId(id);
        order.setUser(user);
        order.setStatus(OrderStatus.PAYMENT_PENDING);
        return order;
    }
}
