package com.sarikaya.e_ticaret.catalog.repository;


import com.sarikaya.e_ticaret.catalog.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long>{

    List<Product> findDistinctByCategories_Id(Long categoryId);

    List<Product> findDistinctByCategories_IdIn(List<Long> categoryIds);

    @Query("SELECT DISTINCT p FROM Product p JOIN p.categories c WHERE c.name = :categoryName")
    List<Product> findByCategoryName(@Param("categoryName") String categoryName);

    //benzer isim
    List<Product> findByNameContainingIgnoreCase(String keyword);

    //fiyat aralığı
    List<Product> findByPriceBetween(Double minPrice, Double maxPrice);
}