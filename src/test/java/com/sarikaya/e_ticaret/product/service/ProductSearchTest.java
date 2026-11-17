package com.sarikaya.e_ticaret.product.service;

import com.sarikaya.e_ticaret.brand.entity.Brand;
import com.sarikaya.e_ticaret.brand.repository.BrandRepository;
import com.sarikaya.e_ticaret.category.entity.Category;
import com.sarikaya.e_ticaret.category.repository.CategoryRepository;
import com.sarikaya.e_ticaret.product.dto.ProductPageResponse;
import com.sarikaya.e_ticaret.product.dto.ProductResponse;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Mağaza araması gerçek veritabanında: filtreler sorguda uygulanır, sonuç sayfalanır.
 * Test kendi ürünlerini oluşturur ve sonda geri alır (@Transactional).
 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Transactional
class ProductSearchTest {
    @Autowired private ProductService service;
    @Autowired private ProductRepository products;
    @Autowired private CategoryRepository categories;
    @Autowired private BrandRepository brands;

    /** Testin ürünleri benzersiz bir adla aranır; veritabanındaki diğer ürünler karışmaz. */
    private final String tag = "srch" + UUID.randomUUID().toString().substring(0, 8);
    private Category fruit;
    private Brand brand;

    @BeforeEach
    void createProducts() {
        fruit = new Category();
        fruit.setName(tag + "-meyve");
        fruit = categories.save(fruit);
        brand = new Brand();
        brand.setName(tag + "-marka");
        brand = brands.save(brand);

        product("elma", "10.00", 5, true, false, fruit, brand);
        product("armut", "20.00", 0, true, false, fruit, null);
        product("kiraz", "30.00", 7, true, false, null, brand);
        product("gizli test", "15.00", 3, true, true, fruit, null);
        product("satıştan kalkmış", "12.00", 3, false, false, fruit, null);
    }

    @Test
    void onlyActiveNonTestProductsAreListedForCustomers() {
        assertEquals(List.of("elma", "armut", "kiraz"), names(search(null, null, null, false, null, false)));
        assertEquals(4, search(null, null, null, false, null, true).totalItems()); // admin test ürününü de görür
    }

    @Test
    void filtersAreAppliedInTheDatabase() {
        assertEquals(List.of("elma", "armut"), names(search(List.of(fruit.getId()), null, null, false, null, false)));
        assertEquals(List.of("armut", "kiraz"), names(search(null, new BigDecimal("15"), null, false, null, false)));
        assertEquals(List.of("elma", "kiraz"), names(search(null, null, null, true, null, false)));
        assertEquals(List.of("elma", "kiraz"), names(search(null, null, null, false, List.of(brand.getId()), false)));
        assertEquals(List.of("elma"), names(search(List.of(fruit.getId()), null, new BigDecimal("15"), true, List.of(brand.getId()), false)));
    }

    @Test
    void resultIsPaged() {
        ProductPageResponse first = service.search(new ProductSearch(tag, null, null, null, false, null, false), 0, 2);
        ProductPageResponse second = service.search(new ProductSearch(tag, null, null, null, false, null, false), 1, 2);

        assertEquals(3, first.totalItems());
        assertEquals(2, first.totalPages());
        assertEquals(2, first.items().size());
        assertEquals(1, second.items().size());
    }

    private ProductPageResponse search(List<Long> categoryIds, BigDecimal min, BigDecimal max, boolean inStock,
                                       List<Long> brandIds, boolean admin) {
        return service.search(new ProductSearch(tag, categoryIds, min, max, inStock, brandIds, admin), 0, 50);
    }

    private List<String> names(ProductPageResponse page) {
        return page.items().stream().map(ProductResponse::name).map(name -> name.substring(tag.length() + 1)).toList();
    }

    private void product(String name, String price, int stock, boolean active, boolean test, Category category, Brand brand) {
        Product product = new Product();
        product.setName(tag + " " + name);
        product.setPrice(new BigDecimal(price));
        product.setStock(stock);
        product.setActive(active);
        product.setTestProduct(test);
        product.setBrand(brand);
        product.setCategories(category == null ? new java.util.LinkedHashSet<>() : new java.util.LinkedHashSet<>(Set.of(category)));
        products.save(product);
    }
}
