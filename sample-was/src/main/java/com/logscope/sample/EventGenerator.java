package com.logscope.sample;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.stereotype.Service;

@Service
class EventGenerator {
  static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
  private final org.slf4j.Logger logger = LoggerFactory.getLogger("SAMPLE_EVENTS");

  String generate(String level, boolean exception, String origin) {
    return generate(level, exception, origin, null);
  }

  String generate(String level, boolean exception, String origin, String scenarioCode) {
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

  String random(String origin) {
    String level = LEVELS.get(ThreadLocalRandom.current().nextInt(LEVELS.size()));
    return generate(level, level.equals("ERROR"), origin);
  }
}
