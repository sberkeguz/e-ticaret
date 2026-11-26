package com.sarikaya.e_ticaret.shipment.web;

import com.sarikaya.e_ticaret.order.dto.AdminOrderResponse;
import com.sarikaya.e_ticaret.order.service.OrderService;
import com.sarikaya.e_ticaret.shipment.service.ShipmentService;
import com.sarikaya.e_ticaret.shipment.service.ShipmentViews;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Yönetici kargo uçları (sözleşme 1.3 / 1.4). İkisi de 202 döner: istek kaydedildi, kargo servisi
 * sonucu olayla bildirecek. Cevap, işlem kaydedildikten sonra okunan güncel AdminOrderResponse'tur.
 * /api/admin/** yalnız ADMIN rolüne açıktır (SecurityConfig).
 */
@RestController
@RequestMapping("/api/admin/orders/{id}/shipment")
public class AdminShipmentController {
    private final ShipmentService shipments;
    private final ShipmentViews views;
    private final OrderService orders;

    public AdminShipmentController(ShipmentService shipments, ShipmentViews views, OrderService orders) {
        this.shipments = shipments;
        this.views = views;
        this.orders = orders;
    }

    public record RetryRequest(@NotNull @Min(1) Integer expectedRequestNo) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AdminOrderResponse ship(@PathVariable Long id) {
        shipments.request(id);
        return views.adminOne(orders.findOne(id));
    }

    @PostMapping("/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AdminOrderResponse retry(@PathVariable Long id, @Valid @RequestBody RetryRequest body) {
        shipments.retry(id, body.expectedRequestNo());
        return views.adminOne(orders.findOne(id));
    }
}
