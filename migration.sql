-- Run once against the original project database after taking a backup.
USE customer_db;

ALTER TABLE users
    CHANGE COLUMN password legacy_password VARCHAR(255) NULL,
    ADD COLUMN password_hash VARCHAR(512) NULL,
    ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN last_login_at TIMESTAMP NULL,
    ADD CONSTRAINT uq_users_username UNIQUE (username);

ALTER TABLE customers
    MODIFY COLUMN email VARCHAR(254) NOT NULL,
    MODIFY COLUMN phone VARCHAR(30) NOT NULL,
    ADD COLUMN status ENUM('ACTIVE', 'PAUSED', 'CANCELLED') NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    ADD CONSTRAINT uq_customers_email UNIQUE (email),
    ADD INDEX idx_customers_name (name),
    ADD INDEX idx_customers_status (status);

CREATE TABLE audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    action VARCHAR(20) NOT NULL,
    subscriber_id INT NULL,
    details VARCHAR(500) NOT NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_occurred_at (occurred_at),
    CONSTRAINT fk_audit_user FOREIGN KEY (user_id) REFERENCES users(id)
);
