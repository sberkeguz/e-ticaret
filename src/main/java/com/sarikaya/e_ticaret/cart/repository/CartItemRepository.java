package com.sarikaya.e_ticaret.cart.repository;

import com.sarikaya.e_ticaret.cart.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    List<CartItem> findByUser_IdOrderByIdAsc(Long userId);

    /**
     * Ürünün stoğu düştüğünde, başka kullanıcıların sepetlerini kalan stoğa uydurur:
     * stok bittiyse ürün sepetlerden silinir, azaldıysa adet kalan stoğa indirilir.
     *
     * @param keepUserId sepetine dokunulmayacak kullanıcı (ör. satın alan); yoksa null
     * @return değişen sepet satırı sayısı
     */
    default int fitToStock(Long productId, int stock, Long keepUserId) {
        long keep = keepUserId == null ? -1L : keepUserId;
        return stock <= 0
                ? deleteProductFromOtherCarts(productId, keep)
                : capQuantityInOtherCarts(productId, stock, keep);
    }

    @Modifying(flushAutomatically = true)
    @Query("delete from CartItem c where c.product.id = :productId and c.user.id <> :keepUserId")
    int deleteProductFromOtherCarts(@Param("productId") Long productId, @Param("keepUserId") Long keepUserId);

    @Modifying(flushAutomatically = true)
    @Query("""
            update CartItem c set c.quantity = :stock
            where c.product.id = :productId and c.user.id <> :keepUserId and c.quantity > :stock""")
    int capQuantityInOtherCarts(@Param("productId") Long productId, @Param("stock") int stock,
                                @Param("keepUserId") Long keepUserId);
}
