package com.sarikaya.e_ticaret.order.dto;

import java.util.List;

/** Sayfalanmış sipariş listesi. page 0'dan başlar. */
public record OrderPageResponse(List<OrderResponse> items, int page, int size, long totalItems, int totalPages) {
}
