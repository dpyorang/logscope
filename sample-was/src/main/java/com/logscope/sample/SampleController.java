package com.logscope.sample;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class SampleController {
  private final EventGenerator generator;
  private final String instance;
  private final long intervalMillis;
  private final AtomicBoolean automatic = new AtomicBoolean(true);

  SampleController(
      EventGenerator generator,
      @Value("${sample.instance-id}") String instance,
      @Value("${sample.interval-ms}") long intervalMillis) {
    if (intervalMillis < 1000 || intervalMillis % 1000 != 0)
      throw new IllegalArgumentException("Interval must use whole positive seconds");
    this.intervalMillis = intervalMillis;
    this.generator = generator;
    this.instance = instance;
  }

  @Scheduled(
      fixedRateString = "${sample.interval-ms}",
      initialDelayString = "${sample.interval-ms}")
  void tick() {
    if (automatic.get()) generator.random("AUTO");
  }

  @GetMapping("/api/status")
  Map<String, Object> status() {
    return Map.of(
        "instanceId",
        instance,
        "automatic",
        automatic.get(),
        "intervalSeconds",
        intervalMillis / 1000);
  }

  @GetMapping("/api/scenarios")
  List<Map<String, String>> scenarios() {
    return CommerceScenarios.ALL.stream()
        .map(
            s ->
                Map.of(
                    "code",
                    s.code(),
                    "category",
                    s.category(),
                    "label",
                    s.label(),
                    "level",
                    s.level()))
        .toList();
  }

  @PostMapping("/api/automatic")
  Map<String, Object> automatic(@RequestBody Toggle input) {
    automatic.set(input.enabled());
    return status();
  }

  @PostMapping("/api/events")
  Map<String, String> create(@RequestBody Input input) {
    try {
      if ("RANDOM".equals(input.level()) && input.scenario() != null)
        throw new IllegalArgumentException("Random level cannot specify scenario");
      String id =
          "RANDOM".equals(input.level()) && !input.exception()
              ? generator.random("MANUAL")
              : generator.generate(input.level(), input.exception(), "MANUAL", input.scenario());
      return Map.of("eventId", id, "instanceId", instance, "status", "LOG_GENERATED");
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Invalid level or exception option");
    }
  }

  record Input(String level, boolean exception, String scenario) {
    Input(String level, boolean exception) {
      this(level, exception, null);
    }
  }

  record Toggle(boolean enabled) {}
}
