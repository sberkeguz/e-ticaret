package com.sarikaya.e_ticaret.order.service;

import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.category.entity.Category;
import com.sarikaya.e_ticaret.order.dto.OrderStatsResponse;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderItem;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderStatsServiceTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final OrderStatsService service = new OrderStatsService(orderRepository, productRepository);

    private final List<Order> orders = new ArrayList<>();
    private Product domates;
    private Product portakal;
    private Product muz;
    private Product testElma;

    @BeforeEach
    void setUp() {
        Category manav = category(1L, "manav");
        Category sebze = category(2L, "sebze");
        Category meyve = category(3L, "meyve");

        domates = product(10L, "Domates", false, manav, sebze);
        portakal = product(11L, "Portakal", false, manav, meyve);
        muz = product(12L, "Muz", false, manav, meyve);
        testElma = product(13L, "Test elma", true, manav, meyve);

        when(orderRepository.findAllByOrderByCreatedAtDesc()).thenReturn(orders);
        when(productRepository.findAllById(any())).thenReturn(List.of(domates, portakal, muz, testElma));
    }

    @Test
    void topProductsCountOnlyCompletedSales() {
        addOrder(OrderStatus.PREPARING, 1, domates, 5);
        addOrder(OrderStatus.DELIVERED, 30, portakal, 9);
        addOrder(OrderStatus.SHIPPED, 2, muz, 2);
        addOrder(OrderStatus.CANCELLED, 1, muz, 50);
        addOrder(OrderStatus.PAYMENT_PENDING, 1, muz, 50);
        addOrder(OrderStatus.PAYMENT_EXPIRED, 1, muz, 50);

        OrderStatsResponse stats = service.stats();

        assertEquals(List.of("Portakal", "Domates", "Muz"), names(stats.topProducts()));
        assertEquals(9, stats.topProducts().getFirst().quantity());
        assertEquals(0, new BigDecimal("9").compareTo(stats.topProducts().getFirst().revenue()));
    }

    @Test
    void testProductsAreLeftOutOfStatistics() {
        addOrder(OrderStatus.PREPARING, 1, testElma, 200);
        addOrder(OrderStatus.PREPARING, 1, domates, 1);

        OrderStatsResponse stats = service.stats();

        assertEquals(List.of("Domates"), names(stats.topProducts()));
        assertEquals(1, stats.topCategories().getFirst().quantity());
    }

    @Test
    void categoriesAddUpProductsInThem() {
        addOrder(OrderStatus.PREPARING, 1, domates, 2);
        addOrder(OrderStatus.PREPARING, 1, portakal, 3);

        OrderStatsResponse stats = service.stats();

        assertEquals("manav", stats.topCategories().getFirst().name());
        assertEquals(5, stats.topCategories().getFirst().quantity());
    }

    @Test
    void trendingShowsOnlyProductsSellingMoreThanLastWeek() {
        addOrder(OrderStatus.PREPARING, 1, domates, 5);
        addOrder(OrderStatus.PREPARING, 10, domates, 1);
        addOrder(OrderStatus.PREPARING, 2, muz, 2);
        addOrder(OrderStatus.PREPARING, 10, muz, 5);
        addOrder(OrderStatus.PREPARING, 20, portakal, 9);

        OrderStatsResponse stats = service.stats();

        assertEquals(List.of("Domates"), names(stats.trendingProducts()));
        assertEquals(1, stats.trendingProducts().getFirst().previous());
        assertEquals(5, stats.trendingProducts().getFirst().recent());
    }

    @Test
    void emptyStoreGivesEmptyLists() {
        OrderStatsResponse stats = service.stats();

        assertTrue(stats.topProducts().isEmpty());
        assertTrue(stats.trendingCategories().isEmpty());
    }

    private void addOrder(OrderStatus status, int daysAgo, Product product, int quantity) {
        Order order = new Order();
        order.setId((long) orders.size() + 1);
        order.setStatus(status);
        order.setCreatedAt(Instant.now().minus(Duration.ofDays(daysAgo)));
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProductId(product.getId());
        item.setProductName(product.getName());
        item.setPrice(BigDecimal.ONE);
        item.setQuantity(quantity);
        order.getItems().add(item);
        orders.add(order);
    }

    private List<String> names(List<?> rows) {
        List<String> names = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof OrderStatsResponse.Ranked ranked) {
                names.add(ranked.name());
            } else if (row instanceof OrderStatsResponse.Trend trend) {
                names.add(trend.name());
            }
        }
        return names;
    }

    private Category category(Long id, String name) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        return category;
    }

    private Product product(Long id, String name, boolean testProduct, Category... categories) {
        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setTestProduct(testProduct);
        product.setCategories(new java.util.LinkedHashSet<>(Set.of(categories)));
        return product;
    }
}
