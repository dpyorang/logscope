package com.logscope.sample;

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
  private final AtomicBoolean automatic = new AtomicBoolean(true);

  SampleController(EventGenerator generator, @Value("${sample.instance-id}") String instance) {
    this.generator = generator;
    this.instance = instance;
  }

  @Scheduled(
      fixedRateString = "${sample.interval-ms:60000}",
      initialDelayString = "${sample.interval-ms:60000}")
  void tick() {
    if (automatic.get()) generator.random("AUTO");
  }

  @GetMapping("/api/status")
  Map<String, Object> status() {
    return Map.of("instanceId", instance, "automatic", automatic.get(), "intervalSeconds", 60);
  }

  @PostMapping("/api/automatic")
  Map<String, Object> automatic(@RequestBody Toggle input) {
    automatic.set(input.enabled());
    return status();
  }

  @PostMapping("/api/events")
  Map<String, String> create(@RequestBody Input input) {
    try {
      String id =
          "RANDOM".equals(input.level()) && !input.exception()
              ? generator.random("MANUAL")
              : generator.generate(input.level(), input.exception(), "MANUAL");
      return Map.of("eventId", id, "instanceId", instance, "status", "LOG_GENERATED");
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Invalid level or exception option");
    }
  }

  record Input(String level, boolean exception) {}

  record Toggle(boolean enabled) {}
}
