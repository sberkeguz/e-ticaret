package com.sarikaya.e_ticaret.order.service;

import com.sarikaya.e_ticaret.brand.entity.Brand;
import com.sarikaya.e_ticaret.exception.ResourceNotFoundException;
import com.sarikaya.e_ticaret.order.dto.OrderDetailResponse;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderItem;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.entity.Payment;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import com.sarikaya.e_ticaret.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderDetailServiceTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final OrderDetailService service =
            new OrderDetailService(orderRepository, paymentRepository, productRepository);

    @BeforeEach
    void setUp() {
        Order order = new Order();
        order.setId(5L);
        order.setUser(User.builder().id(1L).username("berke").build());
        order.setTotal(new BigDecimal("70.00"));
        order.setStatus("Hazırlanıyor");
        order.getItems().add(item(order, 10L, "Domates", "35.00", 2));
        order.getItems().add(item(order, 11L, "Silinmiş ürün", "0.00", 1));
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        Brand brand = new Brand();
        brand.setName("Antalya Sera");
        Product domates = new Product();
        domates.setId(10L);
        domates.setBrand(brand);
        when(productRepository.findAllById(any())).thenReturn(List.of(domates));
    }

    @Test
    void ownerSeesPaidOrderWithBrandsAndTotals() {
        when(paymentRepository.findByOrder_Id(5L)).thenReturn(Optional.of(payment("PAID")));

        OrderDetailResponse detail = service.findMine("berke", 5L);

        assertEquals("Ödendi", detail.paymentStatus());
        assertEquals(0, new BigDecimal("70.00").compareTo(detail.paidAmount()));
        assertEquals("Antalya Sera", detail.items().get(0).brand());
        assertEquals(0, new BigDecimal("70.00").compareTo(detail.items().get(0).lineTotal()));
        assertNull(detail.items().get(1).brand());
    }

    @Test
    void refundedOrderShowsNothingPaid() {
        when(paymentRepository.findByOrder_Id(5L)).thenReturn(Optional.of(payment("REFUNDED")));

        OrderDetailResponse detail = service.findMine("berke", 5L);

        assertEquals("İade edildi", detail.paymentStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(detail.paidAmount()));
    }

    @Test
    void orderWithoutPaymentRecordIsLabelled() {
        when(paymentRepository.findByOrder_Id(5L)).thenReturn(Optional.empty());

        assertEquals("Ödeme kaydı yok", service.findMine("berke", 5L).paymentStatus());
    }

    @Test
    void otherUsersCannotSeeTheOrder() {
        assertThrows(ResourceNotFoundException.class, () -> service.findMine("baskasi", 5L));
    }

    private OrderItem item(Order order, Long productId, String name, String price, int quantity) {
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProductId(productId);
        item.setProductName(name);
        item.setPrice(new BigDecimal(price));
        item.setQuantity(quantity);
        return item;
    }

    private Payment payment(String status) {
        Payment payment = new Payment();
        payment.setStatus(status);
        return payment;
    }
}
