CREATE TABLE log_search_outbox (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT '1000-01-01 00:00:00.000',
    last_error VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    CONSTRAINT fk_log_search_event FOREIGN KEY (event_id) REFERENCES log_events(event_id) ON DELETE CASCADE,
    INDEX idx_search_retry (next_attempt_at, event_id)
);
CREATE TABLE log_search_state (
    id TINYINT NOT NULL PRIMARY KEY,
    index_uuid VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_success_at DATETIME(3) NULL
);
INSERT INTO log_search_state (id) VALUES (1);
INSERT INTO log_search_outbox (event_id) SELECT event_id FROM log_events;
