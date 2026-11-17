package com.sarikaya.e_ticaret.product.service;

import com.sarikaya.e_ticaret.product.entity.Product;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mağaza listesinin filtreleri; hepsi sunucuda, veritabanı sorgusu olarak uygulanır.
 * Boş bırakılan filtre uygulanmaz.
 *
 * @param query        ürün adında aranan metin
 * @param categoryIds  bu kategorilerden birindeki ürünler (alt kategorileri istemci ekler)
 * @param inStock      true ise yalnızca stokta olanlar
 * @param includeTest  test ürünleri (yalnızca admin görür)
 */
public record ProductSearch(String query, List<Long> categoryIds, BigDecimal minPrice, BigDecimal maxPrice,
                            boolean inStock, List<Long> brandIds, boolean includeTest) {

    Specification<Product> toSpecification() {
        return (root, criteria, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.isTrue(root.get("active")));
            if (!includeTest) {
                where.add(cb.isFalse(root.get("testProduct")));
            }
            if (query != null && !query.isBlank()) {
                where.add(cb.like(cb.lower(root.get("name")), "%" + query.trim().toLowerCase(Locale.ROOT) + "%"));
            }
            if (minPrice != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("price"), minPrice));
            }
            if (maxPrice != null) {
                where.add(cb.lessThanOrEqualTo(root.get("price"), maxPrice));
            }
            if (inStock) {
                where.add(cb.greaterThan(root.get("stock"), 0));
            }
            if (brandIds != null && !brandIds.isEmpty()) {
                where.add(root.get("brand").get("id").in(brandIds));
            }
            if (categoryIds != null && !categoryIds.isEmpty()) {
                // Ürün birden çok seçili kategoride olabilir; aynı ürün iki kez gelmesin.
                criteria.distinct(true);
                where.add(root.join("categories").get("id").in(categoryIds));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }
}
