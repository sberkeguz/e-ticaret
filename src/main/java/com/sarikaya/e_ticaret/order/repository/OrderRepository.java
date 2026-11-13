package com.sarikaya.e_ticaret.order.repository;

import com.sarikaya.e_ticaret.order.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    @EntityGraph(attributePaths = {"items", "user"})
    List<Order> findAllByOrderByCreatedAtDesc();

    boolean existsByUserId(Long userId);

    /**
     * Admin sipariş listesi: en yeniler önce, sayfa sayfa. username boş değilse yalnızca o
     * kullanıcının siparişleri; q ("%metin%", küçük harf) kullanıcı adı, ad, soyad, telefon veya
     * ürün adında aranır. Ürünler ve kullanıcı toplu yüklenir (default_batch_fetch_size).
     */
    @Query(value = """
            select o from Order o join o.user u
            where (:username = '' or u.username = :username)
              and (lower(u.username) like :q or lower(o.firstName) like :q or lower(o.lastName) like :q
                   or o.phone like :q
                   or exists (select 1 from OrderItem i where i.order = o and lower(i.productName) like :q))
            order by o.createdAt desc, o.id desc""",
            countQuery = """
            select count(o) from Order o join o.user u
            where (:username = '' or u.username = :username)
              and (lower(u.username) like :q or lower(o.firstName) like :q or lower(o.lastName) like :q
                   or o.phone like :q
                   or exists (select 1 from OrderItem i where i.order = o and lower(i.productName) like :q))""")
    Page<Order> searchForAdmin(@Param("username") String username, @Param("q") String q, Pageable pageable);


    @EntityGraph(attributePaths = {"items","user"})
    List<Order> findAllByUserUsernameOrderByCreatedAtDesc(String username);
}