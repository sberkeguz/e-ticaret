ALTER TABLE users ADD COLUMN last_login_at TIMESTAMP;

CREATE TABLE login_history (
                               id           BIGSERIAL PRIMARY KEY,
                               user_id      BIGINT       NOT NULL,
                               logged_in_at TIMESTAMP    NOT NULL,
                               ip_address   VARCHAR(64),
                               user_agent   VARCHAR(255)
);

CREATE INDEX idx_login_history_user ON login_history (user_id, logged_in_at DESC);