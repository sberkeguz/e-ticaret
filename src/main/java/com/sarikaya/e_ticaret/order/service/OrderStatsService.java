package com.sarikaya.e_ticaret.order.service;

import com.sarikaya.e_ticaret.category.entity.Category;
import com.sarikaya.e_ticaret.order.dto.OrderStatsResponse;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderItem;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Admin Siparişler sayfasındaki özet kartları için satış istatistikleri. */
@Service
public class OrderStatsService {
    /** Yalnızca gerçekleşmiş satışlar sayılır; iptal, ödeme bekleyen veya süresi dolan siparişler hariç. */
    private static final int TREND_WINDOW_DAYS = 7;
    private static final int TOP_LIMIT = 3;

    private final OrderRepository orders;
    private final ProductRepository products;

    public OrderStatsService(OrderRepository orders, ProductRepository products) {
        this.orders = orders;
        this.products = products;
    }

    @Transactional(readOnly = true)
    public OrderStatsResponse stats() {
        List<Order> sold = orders.findAllByOrderByCreatedAtDesc().stream()
                .filter(order -> OrderStatus.SOLD.contains(order.getStatus()))
                .toList();

        Set<Long> productIds = new HashSet<>();
        sold.forEach(order -> order.getItems().forEach(item -> productIds.add(item.getProductId())));
        Map<Long, Product> productsById = products.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        Instant now = Instant.now();
        Instant recentStart = now.minus(Duration.ofDays(TREND_WINDOW_DAYS));
        Instant previousStart = now.minus(Duration.ofDays(TREND_WINDOW_DAYS * 2L));

        Map<Long, Tally> productTotals = new HashMap<>();
        Map<Long, Tally> categoryTotals = new HashMap<>();
        for (Order order : sold) {
            Window window = order.getCreatedAt() == null ? Window.OLDER
                    : !order.getCreatedAt().isBefore(recentStart) ? Window.RECENT
                    : !order.getCreatedAt().isBefore(previousStart) ? Window.PREVIOUS
                    : Window.OLDER;
            for (OrderItem item : order.getItems()) {
                Product product = productsById.get(item.getProductId());
                if (product != null && product.isTestProduct()) {
                    continue;
                }

                BigDecimal lineTotal = item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
                String productName = product != null ? product.getName() : item.getProductName();
                productTotals.computeIfAbsent(item.getProductId(), id -> new Tally(id, productName))
                        .add(item.getQuantity(), lineTotal, window);
                if (product == null) {
                    continue;
                }
                for (Category category : product.getCategories()) {
                    categoryTotals.computeIfAbsent(category.getId(), id -> new Tally(id, category.getName()))
                            .add(item.getQuantity(), lineTotal, window);
                }
            }
        }

        return new OrderStatsResponse(TREND_WINDOW_DAYS,
                top(productTotals), top(categoryTotals),
                trending(productTotals), trending(categoryTotals));
    }

    private static List<OrderStatsResponse.Ranked> top(Map<Long, Tally> totals) {
        return totals.values().stream()
                .sorted(Comparator.comparingLong((Tally tally) -> tally.quantity).reversed()
                        .thenComparing(tally -> tally.name, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(TOP_LIMIT)
                .map(tally -> new OrderStatsResponse.Ranked(tally.id, tally.name, tally.quantity, tally.revenue))
                .toList();
    }

    private static List<OrderStatsResponse.Trend> trending(Map<Long, Tally> totals) {
        return totals.values().stream()
                .filter(tally -> tally.recent > tally.previous)
                .sorted(Comparator.comparingLong((Tally tally) -> tally.recent - tally.previous).reversed()
                        .thenComparing(Comparator.comparingLong((Tally tally) -> tally.recent).reversed())
                        .thenComparing(tally -> tally.name, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(TOP_LIMIT)
                .map(tally -> new OrderStatsResponse.Trend(tally.id, tally.name, tally.recent, tally.previous))
                .toList();
    }

    private enum Window { RECENT, PREVIOUS, OLDER }

    private static final class Tally {
        private final Long id;
        private final String name;
        private long quantity;
        private BigDecimal revenue = BigDecimal.ZERO;
        private long recent;
        private long previous;

        private Tally(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        private void add(int count, BigDecimal amount, Window window) {
            quantity += count;
            revenue = revenue.add(amount);
            if (window == Window.RECENT) recent += count;
            else if (window == Window.PREVIOUS) previous += count;
        }
    }
}
