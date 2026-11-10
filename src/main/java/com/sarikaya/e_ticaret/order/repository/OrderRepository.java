package com.sarikaya.e_ticaret.order.repository;

import com.sarikaya.e_ticaret.order.entity.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    @EntityGraph(attributePaths = {"items", "user"})
    List<Order> findAllByOrderByCreatedAtDesc();

    boolean existsByUserId(Long userId);


    @EntityGraph(attributePaths = {"items","user"})
    List<Order> findAllByUserUsernameOrderByCreatedAtDesc(String username);
}