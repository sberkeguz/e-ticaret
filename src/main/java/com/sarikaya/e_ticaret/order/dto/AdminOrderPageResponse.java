package com.sarikaya.e_ticaret.order.dto;

import java.util.List;

/** Yöneticinin sayfalanmış sipariş listesi (kargo alanlarıyla). page 0'dan başlar. */
public record AdminOrderPageResponse(List<AdminOrderResponse> items, int page, int size, long totalItems,
                                     int totalPages) {
}
