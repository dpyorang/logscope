-- A committed sequence, independent of the source log timestamp, supports SSE replay.
ALTER TABLE log_events ADD COLUMN stream_sequence BIGINT NULL;
UPDATE log_events e JOIN (
    SELECT event_id, ROW_NUMBER() OVER (ORDER BY occurred_at, event_id) AS seq
    FROM log_events
) numbered ON e.event_id = numbered.event_id
SET e.stream_sequence = numbered.seq;
ALTER TABLE log_events MODIFY stream_sequence BIGINT NOT NULL,
    ADD UNIQUE INDEX idx_log_events_stream_sequence (stream_sequence);
CREATE TABLE log_stream_state (
    id TINYINT NOT NULL PRIMARY KEY,
    epoch CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    last_sequence BIGINT NOT NULL
);
INSERT INTO log_stream_state (id, epoch, last_sequence)
SELECT 1, UUID(), COALESCE(MAX(stream_sequence), 0) FROM log_events;
