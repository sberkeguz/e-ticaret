-- IP address and browser details are no longer collected or retained.
ALTER TABLE login_history
    DROP COLUMN IF EXISTS ip_address,
    DROP COLUMN IF EXISTS user_agent;