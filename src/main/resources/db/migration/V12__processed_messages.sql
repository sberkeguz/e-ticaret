-- İşlenen her mesajın numarası. Aynı mesaj ikinci kez gelirse burada bulunur ve atlanır.
CREATE TABLE processed_messages (
    message_id   VARCHAR(100) PRIMARY KEY,
    message_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
