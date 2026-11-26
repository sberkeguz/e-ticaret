package com.sarikaya.e_ticaret.order.controller.user;

import com.sarikaya.e_ticaret.order.dto.OrderDetailResponse;
import com.sarikaya.e_ticaret.order.dto.OrderRequest;
import com.sarikaya.e_ticaret.order.dto.OrderResponse;
import com.sarikaya.e_ticaret.order.service.OrderDetailService;
import com.sarikaya.e_ticaret.order.service.OrderService;
import com.sarikaya.e_ticaret.shipment.service.ShipmentViews;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class UserOrderController {

    private final OrderService orderService;
    private final OrderDetailService orderDetailService;
    private final ShipmentViews shipmentViews;

    public UserOrderController(OrderService orderService, OrderDetailService orderDetailService,
                               ShipmentViews shipmentViews) {
        this.orderService = orderService;
        this.orderDetailService = orderDetailService;
        this.shipmentViews = shipmentViews;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(Authentication auth, @Valid @RequestBody OrderRequest request) {
        return orderService.create(auth.getName(), request);
    }

    /** Kullanıcının kendi siparişleri; kargo bilgisi yalnız takip edilebilir gönderide dolu (yoksa null). */
    @GetMapping
    public List<OrderResponse> mine(Authentication auth) {
        return shipmentViews.customer(orderService.findMine(auth.getName()));
    }

    @GetMapping("/{id}/details")
    public OrderDetailResponse details(Authentication auth, @PathVariable Long id) {
        return orderDetailService.findMine(auth.getName(), id);
    }
}
