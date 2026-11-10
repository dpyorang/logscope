package com.logscope.sample.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.logscope.sample.domain.CommerceScenarios;
import com.logscope.sample.dto.SampleRequests;
import com.logscope.sample.logging.JsonLogEncoder;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SampleTests {
  @Test
  void generatesAllLevelsWithStableIdsAndSingleLineJsonIncludingStackTrace() {
    Logger logger = (Logger) LoggerFactory.getLogger("SAMPLE_EVENTS");
    ListAppender<ILoggingEvent> appender =
        new ListAppender<ILoggingEvent>() {
          @Override
          protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
          }
        };
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(ch.qos.logback.classic.Level.TRACE);
    JsonLogEncoder encoder = new JsonLogEncoder();
    encoder.setInstanceId("was-2");
    HashSet<String> ids = new HashSet<String>();
    try {
      EventGenerator generator = new EventGenerator();
      for (String level : EventGenerator.LEVELS) {
        String id = generator.generate(level, level.equals("ERROR"), "MANUAL");
        assertThat(ids.add(id)).isTrue();
        assertThat(java.util.UUID.fromString(id).version()).isEqualTo(4);
        ILoggingEvent event = appender.list.getLast();
        // The appender freezes MDC while the eventId is still in scope.
        byte[] bytes = encoder.encode(event);
        JsonNode json = JsonMapper.builder().build().readTree(bytes);
        assertThat(json.get("eventId").asString()).isEqualTo(id);
        assertThat(json.get("level").asString()).isEqualTo(level);
        assertThat(json.get("instanceId").asString()).isEqualTo("was-2");
        assertThat(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).lines().count())
            .isEqualTo(1);
        assertThat(json.get("occurredAt").asString()).matches(".*\\.\\d{3}Z");
        if (level.equals("ERROR"))
          assertThat(json.get("exception").asString())
              .contains("synthetic-", "CommerceScenarios", "EventGenerator.generate");
        else assertThat(json.get("exception").isNull()).isTrue();
      }
      assertThatThrownBy(() -> generator.generate(null, false, "MANUAL", null))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> generator.generate("FATAL", false, "MANUAL"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> generator.generate("INFO", true, "MANUAL"))
          .isInstanceOf(IllegalArgumentException.class);
      for (int i = 0; i < 20; i++) {
        String id = generator.random("AUTO");
        ILoggingEvent event = appender.list.getLast();
        assertThat(EventGenerator.LEVELS).contains(event.getLevel().toString());
        assertThat(event.getMDCPropertyMap()).containsEntry("eventId", id);
        assertThat(event.getFormattedMessage()).startsWith("[AUTO] [합성]");
        assertThat(event.getThrowableProxy() != null)
            .isEqualTo(event.getLevel().toString().equals("ERROR"));
      }
      assertThat(org.slf4j.MDC.get("eventId")).isNull();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void controllerHonorsPauseAndRoutesManualChoicesWithoutClaimingCentralStorage() {
    EventGenerator generator = mock(EventGenerator.class);
    when(generator.random("AUTO")).thenReturn("synthetic");
    when(generator.random("MANUAL")).thenReturn("synthetic");
    SampleService controller = new SampleService(generator, "was-1", 30000);
    assertThat(controller.status()).containsEntry("intervalSeconds", 30L);
    assertThat(controller.scenarios()).hasSize(20);
    controller.tick();
    verify(generator).random("AUTO");
    controller.automatic(new SampleRequests.Toggle(false));
    controller.tick();
    verifyNoMoreInteractions(generator);
    assertThat(controller.create(new SampleRequests.Input("RANDOM", false)))
        .containsEntry("status", "LOG_GENERATED");
    when(generator.generate(null, false, "MANUAL", null)).thenThrow(new IllegalArgumentException());
    assertThatThrownBy(() -> controller.create(new SampleRequests.Input(null, false)))
        .isInstanceOf(IllegalArgumentException.class);
    controller.automatic(new SampleRequests.Toggle(true));
    controller.tick();
    verify(generator, times(2)).random("AUTO");
  }

  @Test
  void everyCommerceScenarioHasStableCodeAndRealisticContextWithoutChangingEnvelope() {
    Logger logger = (Logger) LoggerFactory.getLogger("SAMPLE_EVENTS");
    ListAppender<ILoggingEvent> appender =
        new ListAppender<ILoggingEvent>() {
          @Override
          protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
          }
        };
    appender.start();
    logger.addAppender(appender);
    Level previous = logger.getLevel();
    logger.setLevel(ch.qos.logback.classic.Level.TRACE);
    JsonLogEncoder encoder = new JsonLogEncoder();
    encoder.setInstanceId("was-1");
    EventGenerator generator = new EventGenerator();
    HashSet<String> codes = new HashSet<String>();
    try {
      for (CommerceScenarios.Scenario scenario : CommerceScenarios.ALL) {
        assertThat(codes.add(scenario.code())).isTrue();
        assertThat(scenario.code()).matches("[A-Z_]+");
        assertThat(scenario.detail()).contains("synthetic-");
        String id =
            generator.generate(
                scenario.level(), scenario.level().equals("ERROR"), "MANUAL", scenario.code());
        JsonNode data =
            JsonMapper.builder().build().readTree(encoder.encode(appender.list.getLast()));
        assertThat(data.size()).isEqualTo(7);
        assertThat(data.path("eventId").asString()).isEqualTo(id);
        assertThat(data.path("message").asString()).isEqualTo("[MANUAL] " + scenario.message());
        assertThat(data.path("level").asString()).isEqualTo(scenario.level());
        if (scenario.level().equals("ERROR")) {
          assertThat(data.path("exception").asString()).contains(scenario.detail());
          if (scenario.code().equals("PAYMENT_TIMEOUT"))
            assertThat(data.path("exception").asString())
                .contains("SocketTimeoutException", "Caused by");
          generator.generate("ERROR", false, "MANUAL", scenario.code());
          assertThat(appender.list.getLast().getThrowableProxy()).isNull();
        } else {
          assertThat(data.path("exception").isNull()).isTrue();
          assertThatThrownBy(scenario::failure).isInstanceOf(IllegalArgumentException.class);
        }
      }
      int before = appender.list.size();
      assertThatThrownBy(() -> generator.generate("ERROR", true, "MANUAL", "UNKNOWN"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> generator.generate("INFO", false, "MANUAL", "PAYMENT_TIMEOUT"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> generator.generate("INFO", false, "external", null))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(appender.list).hasSize(before);
      assertThat(org.slf4j.MDC.get("eventId")).isNull();
    } finally {
      logger.setLevel(previous);
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void scenarioSelectionRejectsContradictoryRequestsAndIntervalStatusUsesConfiguration() {
    EventGenerator generator = mock(EventGenerator.class);
    SampleService controller = new SampleService(generator, "was-2", 45000);
    assertThat(controller.status()).containsEntry("intervalSeconds", 45L);
    when(generator.generate("ERROR", true, "MANUAL", "PAYMENT_TIMEOUT")).thenReturn("synthetic");
    assertThat(controller.create(new SampleRequests.Input("ERROR", true, "PAYMENT_TIMEOUT")))
        .containsEntry("eventId", "synthetic")
        .containsEntry("status", "LOG_GENERATED");
    verify(generator).generate("ERROR", true, "MANUAL", "PAYMENT_TIMEOUT");
    assertThatThrownBy(
            () -> controller.create(new SampleRequests.Input("RANDOM", false, "PAYMENT_TIMEOUT")))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoMoreInteractions(generator);
    assertThatThrownBy(() -> new SampleService(generator, "was-1", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SampleService(generator, "was-1", 1500))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
