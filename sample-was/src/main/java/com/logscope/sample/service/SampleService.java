package com.logscope.sample.service;

import com.logscope.sample.domain.CommerceScenarios;
import com.logscope.sample.dto.SampleRequests.Input;
import com.logscope.sample.dto.SampleRequests.Toggle;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 자동·수동 합성 로그 생성의 상태와 처리 흐름을 담당한다. */
@Service
public class SampleService {
  private final EventGenerator generator;
  private final String instance;
  private final long intervalMillis;
  private final AtomicBoolean automatic = new AtomicBoolean(true);

  /** 샘플 식별자와 초 단위 주기를 검증하고 생성기를 연결한다. */
  public SampleService(
      EventGenerator generator,
      @Value("${sample.instance-id}") String instance,
      @Value("${sample.interval-ms}") long intervalMillis) {
    if (intervalMillis < 1000 || intervalMillis % 1000 != 0)
      throw new IllegalArgumentException("Interval must use whole positive seconds");
    this.intervalMillis = intervalMillis;
    this.generator = generator;
    this.instance = instance;
  }

  /** 자동 생성이 켜져 있을 때 설정 주기마다 합성 로그 한 건을 생성한다. */
  @Scheduled(
      fixedRateString = "${sample.interval-ms}",
      initialDelayString = "${sample.interval-ms}")
  public void tick() {
    if (automatic.get()) generator.random("AUTO");
  }

  /** 현재 서버·자동 생성 여부·실제 설정 주기를 제공한다. */
  public Map<String, Object> status() {
    return Map.of(
        "instanceId",
        instance,
        "automatic",
        automatic.get(),
        "intervalSeconds",
        intervalMillis / 1000);
  }

  /** 정의된 상황을 샘플 화면의 선택 목록으로 변환한다. */
  public List<Map<String, String>> scenarios() {
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

  /** 메모리의 자동 생성 상태를 바꾸고 변경된 상태를 반환한다. */
  public Map<String, Object> automatic(Toggle input) {
    automatic.set(input.enabled());
    return status();
  }

  /** 선택한 레벨·상황으로 로그를 생성하며 중앙 저장 완료를 보장하지 않는다. */
  public Map<String, String> create(Input input) {
    if ("RANDOM".equals(input.level()) && input.scenario() != null)
      throw new IllegalArgumentException("Random level cannot specify scenario");
    String id =
        "RANDOM".equals(input.level()) && !input.exception()
            ? generator.random("MANUAL")
            : generator.generate(input.level(), input.exception(), "MANUAL", input.scenario());
    return Map.of("eventId", id, "instanceId", instance, "status", "LOG_GENERATED");
  }
}
