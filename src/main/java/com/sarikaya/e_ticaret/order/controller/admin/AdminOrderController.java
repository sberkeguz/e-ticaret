package com.sarikaya.e_ticaret.order.controller.admin;

import com.sarikaya.e_ticaret.order.dto.OrderResponse;
import com.sarikaya.e_ticaret.order.service.OrderService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public List<OrderResponse> listOrders() {
        return orderService.findAll();
    }

    @PatchMapping("/{id}/status")
    public OrderResponse updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return orderService.updateStatus(id, body.get("status"));
    }
}
