package com.sarikaya.e_ticaret.product.repository;

import com.sarikaya.e_ticaret.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param; 

import java.util.List;
import java.util.Optional;



public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findAllByActiveTrue();

    List<Product> findAllByActiveTrueAndTestProductFalse();


@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select p from Product p where p.id = :id")
Optional<Product> findByIdForUpdate(@Param("id") Long id);

    Optional<Product> findByIdAndActiveTrue(Long id);
}
