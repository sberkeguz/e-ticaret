CREATE TABLE IF NOT EXISTS brands (
                                      id BIGSERIAL PRIMARY KEY,
                                      name VARCHAR(255) NOT NULL UNIQUE
    );

ALTER TABLE products
    ADD COLUMN IF NOT EXISTS brand_id BIGINT,
    ADD COLUMN IF NOT EXISTS quantity INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS height_cm NUMERIC(8, 2),
    ADD COLUMN IF NOT EXISTS unit VARCHAR(20) NOT NULL DEFAULT 'PIECE',
    ADD COLUMN IF NOT EXISTS photo_url VARCHAR(1000);

CREATE TABLE IF NOT EXISTS product_categories (
                                                  product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    category_id BIGINT NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    PRIMARY KEY (product_id, category_id)
    );

INSERT INTO product_categories (product_id, category_id)
SELECT id, category_id
FROM products
WHERE category_id IS NOT NULL
    ON CONFLICT DO NOTHING;

ALTER TABLE products
DROP COLUMN IF EXISTS category_id;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_products_brand'
          AND conrelid = 'products'::regclass
    ) THEN
ALTER TABLE products
    ADD CONSTRAINT fk_products_brand
        FOREIGN KEY (brand_id) REFERENCES brands(id);
END IF;
END $$;