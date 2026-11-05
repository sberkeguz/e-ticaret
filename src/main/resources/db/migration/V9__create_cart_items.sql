CREATE TABLE cart_items (
                            id         BIGSERIAL PRIMARY KEY,
                            user_id    BIGINT  NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
                            product_id BIGINT  NOT NULL REFERENCES products(id) ON DELETE CASCADE,
                            quantity   INTEGER NOT NULL CHECK (quantity > 0),

                            CONSTRAINT uq_cart_user_product UNIQUE (user_id, product_id)
);