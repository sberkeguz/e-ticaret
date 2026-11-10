package com.sarikaya.e_ticaret.order.service;

import com.sarikaya.e_ticaret.exception.ResourceNotFoundException;
import com.sarikaya.e_ticaret.order.dto.OrderDetailResponse;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.payment.entity.Payment;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderDetailService {
    private static final Map<String, String> PAYMENT_LABELS = Map.of(
            "PAID", "Ödendi",
            "REVIEW", "Ödeme inceleniyor",
            "PENDING", "Ödeme bekliyor",
            "FAILED", "Ödeme başarısız",
            "CANCELLED", "İptal edildi",
            "EXPIRED", "Ödeme süresi doldu",
            "REFUNDED", "İade edildi");
    private static final Set<String> COLLECTED = Set.of("PAID", "REVIEW");

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final ProductRepository products;

    public OrderDetailService(OrderRepository orders, PaymentRepository payments, ProductRepository products) {
        this.orders = orders;
        this.payments = payments;
        this.products = products;
    }

    @Transactional(readOnly = true)
    public OrderDetailResponse findMine(String username, Long orderId) {
        Order order = orders.findById(orderId)
                .filter(found -> found.getUser() != null && username.equals(found.getUser().getUsername()))
                .orElseThrow(() -> new ResourceNotFoundException("Sipariş bulunamadı."));

        String paymentStatus = payments.findByOrder_Id(orderId).map(Payment::getStatus).orElse(null);
        String paymentLabel = paymentStatus == null
                ? "Ödeme kaydı yok"
                : PAYMENT_LABELS.getOrDefault(paymentStatus, paymentStatus);
        BigDecimal paidAmount = paymentStatus != null && COLLECTED.contains(paymentStatus)
                ? order.getTotal()
                : BigDecimal.ZERO;

        Map<Long, Product> productsById = products.findAllById(
                        order.getItems().stream().map(item -> item.getProductId()).toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));

        var items = order.getItems().stream().map(item -> {
            Product product = productsById.get(item.getProductId());
            String brand = product != null && product.getBrand() != null ? product.getBrand().getName() : null;
            return new OrderDetailResponse.Item(item.getProductId(), item.getProductName(), brand,
                    item.getPrice(), item.getQuantity(),
                    item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }).toList();

        return new OrderDetailResponse(order.getId(), order.getCreatedAt(), order.getStatus(), paymentLabel,
                order.getTotal(), paidAmount, order.getFirstName(), order.getLastName(), order.getPhone(),
                order.getCity(), order.getDistrict(), order.getNeighborhood(), order.getAddress(), items);
    }
}
