package com.sarikaya.e_ticaret.order.service;

import com.sarikaya.e_ticaret.exception.BadRequestException;
import com.sarikaya.e_ticaret.exception.ResourceNotFoundException;
import com.sarikaya.e_ticaret.order.dto.OrderRequest;
import com.sarikaya.e_ticaret.order.dto.OrderResponse;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderItem;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class OrderService {
    private final OrderRepository orders;
    private final ProductRepository products;
    private final UserRepository users;

    public OrderService(OrderRepository orders, ProductRepository products, UserRepository users) {
        this.orders = orders;
        this.products = products;
        this.users = users;
    }

    @Transactional
    public OrderResponse create(String username, OrderRequest request) {
        User user = users.findByUsername(username).orElseThrow(() -> new ResourceNotFoundException("Kullanıcı bulunamadı"));

        Order order = new Order();
        order.setUser(user);
        order.setFirstName(request.firstName().trim());
        order.setLastName(request.lastName().trim());
        order.setPhone(request.phone().trim());
        order.setCity(request.city().trim());
        order.setDistrict(request.district().trim());
        order.setNeighborhood(request.neighborhood().trim());
        order.setAddress(request.address().trim());

        BigDecimal total = BigDecimal.ZERO;
        for (OrderRequest.Item line : request.items()) {
            Product product = products.findByIdForUpdate(line.productId())
                    .orElseThrow(() -> new ResourceNotFoundException("Ürün bulunamadı: " + line.productId()));
            if (!product.isActive()) {
                throw new BadRequestException(product.getName() + " artık satışta değil.");
            }
            int stock = product.getStock() == null ? 0 : product.getStock();
            if (stock < line.quantity()) {
                throw new BadRequestException(product.getName() + " için yeterli stok yok (kalan: " + stock + ").");
            }
            product.setStock(stock - line.quantity());
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProductId(product.getId());
            item.setProductName(product.getName());
            item.setPrice(product.getPrice());
            item.setQuantity(line.quantity());
            order.getItems().add(item);
            total = total.add(product.getPrice().multiply(BigDecimal.valueOf(line.quantity())));
        }
        order.setTotal(total);
        return toResponse(orders.save(order));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findAll() {
        return orders.findAllByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
    }

    private OrderResponse toResponse(Order o) {
        return new OrderResponse(o.getId(), o.getUser().getUsername(), o.getFirstName(), o.getLastName(),
                o.getPhone(), o.getCity(), o.getDistrict(), o.getNeighborhood(), o.getAddress(), o.getTotal(), o.getStatus(),
                o.getCreatedAt(),
                o.getItems().stream()
                        .map(i -> new OrderResponse.Item(i.getProductId(), i.getProductName(), i.getPrice(), i.getQuantity()))
                        .toList());
    }


    @Transactional(readOnly = true)
    public List<OrderResponse> findMine(String username) {
        List<Order> myOrders = orders.findAllByUserUsernameOrderByCreatedAtDesc(username);

        List<OrderResponse> responses = new ArrayList<>();
        for (Order order : myOrders) {
            responses.add(toResponse(order));
        }
        return responses;
    }

    @Transactional
    public OrderResponse updateStatus(Long id, OrderStatus newStatus) {
        boolean isValid = newStatus != null && OrderStatus.ADMIN_SELECTABLE.contains(newStatus);
        if (!isValid) {
            throw new BadRequestException("Geçersiz sipariş durumu.");
        }

        Order order = orders.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sipariş bulunamadı."));

        order.setStatus(newStatus);

        return toResponse(order);
    }
}