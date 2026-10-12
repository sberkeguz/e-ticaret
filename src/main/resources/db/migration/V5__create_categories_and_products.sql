CREATE TABLE categories (
                            id BIGSERIAL PRIMARY KEY,
                            name VARCHAR(255) NOT NULL,
                            category_id BIGINT,
                            CONSTRAINT fk_category_parent
                                FOREIGN KEY (category_id)
                                    REFERENCES categories(id)
);

CREATE TABLE products (
                          id BIGSERIAL PRIMARY KEY,
                          name VARCHAR(255) NOT NULL,
                          category_id BIGINT NOT NULL,
                          price NUMERIC(12,2),
                          description TEXT,
                          CONSTRAINT fk_product_category
                              FOREIGN KEY (category_id)
                                  REFERENCES categories(id)
);