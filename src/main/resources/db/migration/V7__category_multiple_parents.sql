-- Kategori ile üst kategorileri arasında çoktan çoğa ilişki tablosu oluşturur.
CREATE TABLE category_parents (
                                  category_id BIGINT NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
                                  parent_id BIGINT NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
                                  CONSTRAINT pk_category_parents PRIMARY KEY (category_id, parent_id),
                                  CONSTRAINT chk_category_parent_not_self CHECK (category_id <> parent_id)
);

-- Eski tekli ilişkileri yeni tabloya aktarır.
INSERT INTO category_parents (category_id, parent_id)
SELECT id, category_id
FROM categories
WHERE category_id IS NOT NULL
    ON CONFLICT DO NOTHING;

-- Eski tekli ilişki sütununu kaldırır.
ALTER TABLE categories
DROP COLUMN IF EXISTS category_id;