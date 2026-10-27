CREATE TABLE log_events (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    service_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    instance_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    level VARCHAR(5) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message VARCHAR(4096) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    exception MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    PRIMARY KEY (event_id),
    INDEX idx_log_events_occurred_event (occurred_at DESC, event_id DESC)
);
