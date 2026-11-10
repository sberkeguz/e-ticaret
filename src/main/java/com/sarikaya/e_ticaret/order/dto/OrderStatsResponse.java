package com.sarikaya.e_ticaret.order.dto;

import java.math.BigDecimal;
import java.util.List;

public record OrderStatsResponse(
        int trendWindowDays,
        List<Ranked> topProducts,
        List<Ranked> topCategories,
        List<Trend> trendingProducts,
        List<Trend> trendingCategories) {

    public record Ranked(Long id, String name, long quantity, BigDecimal revenue) {}

    /** recent: son pencerede satılan adet, previous: ondan önceki eşit uzunluktaki pencere. */
    public record Trend(Long id, String name, long recent, long previous) {}
}
