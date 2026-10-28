package com.logscope.sample.service;

import com.logscope.sample.domain.CommerceScenarios;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.stereotype.Service;

/** 합성 상황을 전용 Logback 로그 이벤트로 기록한다. */
@Service
public class EventGenerator {
  public static final List<String> LEVELS = CommerceScenarios.LEVELS;
  private final org.slf4j.Logger logger = LoggerFactory.getLogger("SAMPLE_EVENTS");

  /** 레벨·상황을 검증하고 eventId를 한 번 발급해 전용 Logback logger에 기록한다. */
  public String generate(String level, boolean exception, String origin) {
    return generate(level, exception, origin, null);
  }

  /** 레벨·상황을 검증하고 eventId를 한 번 발급해 전용 Logback logger에 기록한다. */
  public String generate(String level, boolean exception, String origin, String scenarioCode) {
    if (level == null || !LEVELS.contains(level) || (exception && !level.equals("ERROR")))
      throw new IllegalArgumentException("Invalid sample level");
    if (!List.of("AUTO", "MANUAL").contains(origin))
      throw new IllegalArgumentException("Invalid origin");
    var scenario = CommerceScenarios.select(level, scenarioCode);
    String id = UUID.randomUUID().toString();
    try (var ignored = MDC.putCloseable("eventId", id)) {
      var builder = logger.atLevel(Level.valueOf(level));
      if (exception) builder.setCause(scenario.failure());
      builder.log("[{}] {}", origin, scenario.message());
    }
    return id;
  }

  /** 랜덤 레벨의 합성 상황을 고르고 ERROR인 경우 예외 원인도 함께 기록한다. */
  public String random(String origin) {
    String level = LEVELS.get(ThreadLocalRandom.current().nextInt(LEVELS.size()));
    return generate(level, level.equals("ERROR"), origin);
  }
}
