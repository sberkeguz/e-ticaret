package com.sarikaya.e_ticaret.product.dto;

import java.util.List;

/** Sayfalanmış ürün listesi. page 0'dan başlar. */
public record ProductPageResponse(List<ProductResponse> items, int page, int size, long totalItems, int totalPages) {
}
