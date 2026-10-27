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
    if (level == null || !LEVELS.contains(level) || (exception && !level.equals("ERROR")))
      throw new IllegalArgumentException("Invalid sample level");
    String id = UUID.randomUUID().toString();
    try (var ignored = MDC.putCloseable("eventId", id)) {
      var builder = logger.atLevel(Level.valueOf(level));
      if (exception) {
        try {
          throw new IllegalStateException("Synthetic sample failure");
        } catch (IllegalStateException e) {
          builder.setCause(e);
        }
      }
      builder.log("[{}] Synthetic {} event", origin, level);
    }
    return id;
  }

  String random(String origin) {
    return generate(LEVELS.get(ThreadLocalRandom.current().nextInt(LEVELS.size())), false, origin);
  }
}
