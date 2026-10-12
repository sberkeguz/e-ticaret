package com.sarikaya.e_ticaret.repository;


import com.sarikaya.e_ticaret.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long>{

    //kategori bazlı ürünler
    List<Product> findByCategoryId(long categoryId);

    // isim ile filtre
    @Query("SELECT p FROM Product p JOIN p.category c WHERE c.name = :categoryName")
    List<Product> findByCategoryName(@Param("categoryName")String categoryName);

    //benzer isim
    List<Product> findByNameContainingIgnoreCase(String keyword);

    //fiyat aralığı
    List<Product> findByPriceBetween(Double minPrice, Double maxPrice);
}