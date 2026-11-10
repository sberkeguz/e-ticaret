package com.sarikaya.e_ticaret.order.controller.admin;

import com.sarikaya.e_ticaret.order.dto.OrderResponse;
import com.sarikaya.e_ticaret.order.dto.OrderStatsResponse;
import com.sarikaya.e_ticaret.order.service.AdminOrderStatusService;
import com.sarikaya.e_ticaret.order.service.OrderStatsService;
import jakarta.servlet.http.HttpServletRequest;
import com.sarikaya.e_ticaret.order.service.OrderService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;
    private final OrderStatsService orderStatsService;
    private final AdminOrderStatusService adminOrderStatusService;

    public AdminOrderController(OrderService orderService,
                                OrderStatsService orderStatsService,
                                AdminOrderStatusService adminOrderStatusService) {
        this.orderService = orderService;
        this.orderStatsService = orderStatsService;
        this.adminOrderStatusService = adminOrderStatusService;
    }

    @GetMapping("/stats")
    public OrderStatsResponse stats() {
        return orderStatsService.stats();
    }

    @GetMapping
    public List<OrderResponse> listOrders() {
        return orderService.findAll();
    }

    @PatchMapping("/{id}/status")
    public OrderResponse updateStatus(@PathVariable Long id,
                                      @RequestBody Map<String, String> body,
                                      HttpServletRequest request) {
        String newStatus = body.get("status");
        String adminIp = request.getRemoteAddr();
        return adminOrderStatusService.changeStatus(id, newStatus, adminIp);
    }
}
