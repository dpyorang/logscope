package com.logscope.log.repository;

import com.logscope.log.domain.LogEvent;
import com.logscope.log.dto.LogResponses.Synchronization;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 검색 상태와 영속 색인 대기열의 SQL·트랜잭션을 담당한다. */
@Repository
public class LogSearchRepository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  /** 검색 DB 접근과 read-committed 동기화 트랜잭션을 준비한다. */
  public LogSearchRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(manager);
    transactions.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /** 색인 상태 행을 잠근 트랜잭션 안에서 재구성·전송·확인 처리를 직렬화한다. */
  public void withLockedState(Consumer<String> work) {
    transactions.executeWithoutResult(
        tx ->
            work.accept(
                jdbc.queryForObject(
                    "SELECT index_uuid FROM log_search_state WHERE id=1 FOR UPDATE",
                    String.class)));
  }

  /** MySQL에 기록한 검색 색인의 세대 UUID를 읽는다. */
  public String indexUuid() {
    return jdbc.queryForObject("SELECT index_uuid FROM log_search_state WHERE id=1", String.class);
  }

  /** 전체 색인 대기·재시도 건수를 관측하며 검색 매칭 건수로 해석하지 않는다. */
  public Synchronization synchronization() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) AS pending,COALESCE(SUM(attempts>0),0) AS retrying FROM log_search_outbox",
        (rs, row) ->
            new Synchronization(
                rs.getLong("pending"), rs.getLong("retrying"), rs.getLong("pending") > 0));
  }

  /** 색인 세대가 바뀌면 원본 전체를 다시 대기열에 넣고 새 UUID를 기록한다. */
  public void rebuild(String uuid) {
    jdbc.update(
        "INSERT INTO log_search_outbox (event_id) SELECT event_id FROM log_events ON DUPLICATE KEY UPDATE attempts=0, next_attempt_at='1000-01-01 00:00:00.000', last_error=NULL");
    jdbc.update("UPDATE log_search_state SET index_uuid=?,last_success_at=NULL WHERE id=1", uuid);
  }

  /** 재시도 시각이 된 작업을 원본 저장 순서로 최대 50건 읽는다. */
  public List<Pending> readyBatch() {
    return jdbc.query(
        "SELECT e.event_id,e.occurred_at,e.service_name,e.instance_id,e.level,e.message,(e.exception IS NOT NULL) AS has_exception,o.attempts FROM log_search_outbox o JOIN log_events e ON e.event_id=o.event_id WHERE o.next_attempt_at<=UTC_TIMESTAMP(3) ORDER BY e.stream_sequence LIMIT 50",
        (rs, row) ->
            new Pending(
                new LogEvent.Summary(
                    rs.getString("event_id"),
                    new java.time.format.DateTimeFormatterBuilder()
                        .appendInstant(3)
                        .toFormatter()
                        .format(
                            rs.getObject("occurred_at", LocalDateTime.class)
                                .toInstant(ZoneOffset.UTC)),
                    rs.getString("service_name"),
                    rs.getString("instance_id"),
                    rs.getString("level"),
                    rs.getString("message"),
                    rs.getBoolean("has_exception")),
                rs.getInt("attempts")));
  }

  /** 검색 가능 반영을 확인한 한 작업을 대기열에서 제거한다. */
  public void acknowledge(String eventId) {
    jdbc.update("DELETE FROM log_search_outbox WHERE event_id=?", eventId);
  }

  /** 실패 작업을 보존하고 다음 시도 시각과 제한된 오류 코드를 기록한다. */
  public void retry(String eventId, long seconds) {
    jdbc.update(
        "UPDATE log_search_outbox SET attempts=LEAST(attempts+1,30),next_attempt_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)),last_error='INDEX_RETRY' WHERE event_id=?",
        seconds,
        eventId);
  }

  /** 한 항목 이상 색인 성공을 확인한 시각을 기록한다. */
  public void markSuccess() {
    jdbc.update("UPDATE log_search_state SET last_success_at=UTC_TIMESTAMP(3) WHERE id=1");
  }

  public record Pending(LogEvent.Summary item, int attempts) {}
}
