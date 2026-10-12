ALTER TABLE categories
    ADD COLUMN IF NOT EXISTS category_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_category_parent'
    ) THEN
ALTER TABLE categories
    ADD CONSTRAINT fk_category_parent
        FOREIGN KEY (category_id) REFERENCES categories(id);
END IF;
END $$;

CREATE TABLE IF NOT EXISTS products (
                                        id BIGSERIAL PRIMARY KEY,
                                        name VARCHAR(255) NOT NULL,
    category_id BIGINT NOT NULL,
    price NUMERIC(12, 2),
    description TEXT,
    CONSTRAINT fk_product_category
    FOREIGN KEY (category_id) REFERENCES categories(id)
    );