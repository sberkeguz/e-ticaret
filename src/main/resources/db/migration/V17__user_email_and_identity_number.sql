-- Profilde saklanan alıcı bilgileri: ödeme formu bunlarla otomatik dolar (ikisi de isteğe bağlı).
ALTER TABLE users ADD COLUMN email VARCHAR(254);
ALTER TABLE users ADD COLUMN identity_number VARCHAR(11);
