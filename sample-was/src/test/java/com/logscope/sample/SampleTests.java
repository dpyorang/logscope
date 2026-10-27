package com.logscope.sample;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class SampleTests {
  @Test
  void generatesAllLevelsWithStableIdsAndSingleLineJsonIncludingStackTrace() {
    Logger logger = (Logger) LoggerFactory.getLogger("SAMPLE_EVENTS");
    var appender =
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
    var encoder = new JsonLogEncoder();
    encoder.setInstanceId("was-2");
    var ids = new HashSet<String>();
    try {
      var generator = new EventGenerator();
      for (String level : EventGenerator.LEVELS) {
        String id = generator.generate(level, level.equals("ERROR"), "MANUAL");
        assertThat(ids.add(id)).isTrue();
        assertThat(java.util.UUID.fromString(id).version()).isEqualTo(4);
        var event = appender.list.getLast();
        // The appender freezes MDC while the eventId is still in scope.
        var bytes = encoder.encode(event);
        var json = JsonMapper.builder().build().readTree(bytes);
        assertThat(json.get("eventId").asString()).isEqualTo(id);
        assertThat(json.get("level").asString()).isEqualTo(level);
        assertThat(json.get("instanceId").asString()).isEqualTo("was-2");
        assertThat(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).lines().count())
            .isEqualTo(1);
        assertThat(json.get("occurredAt").asString()).matches(".*\\.\\d{3}Z");
        if (level.equals("ERROR"))
          assertThat(json.get("exception").asString())
              .contains("Synthetic sample failure", "EventGenerator.generate");
        else assertThat(json.get("exception").isNull()).isTrue();
      }
      assertThatThrownBy(() -> generator.generate(null, false, "MANUAL"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> generator.generate("FATAL", false, "MANUAL"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> generator.generate("INFO", true, "MANUAL"))
          .isInstanceOf(IllegalArgumentException.class);
      for (int i = 0; i < 20; i++) {
        String id = generator.random("AUTO");
        var event = appender.list.getLast();
        assertThat(EventGenerator.LEVELS).contains(event.getLevel().toString());
        assertThat(event.getMDCPropertyMap()).containsEntry("eventId", id);
        assertThat(event.getFormattedMessage()).startsWith("[AUTO]");
      }
      assertThat(org.slf4j.MDC.get("eventId")).isNull();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void controllerHonorsPauseAndRoutesManualChoicesWithoutClaimingCentralStorage() {
    var generator = mock(EventGenerator.class);
    when(generator.random("AUTO")).thenReturn("synthetic");
    when(generator.random("MANUAL")).thenReturn("synthetic");
    var controller = new SampleController(generator, "was-1");
    controller.tick();
    verify(generator).random("AUTO");
    controller.automatic(new SampleController.Toggle(false));
    controller.tick();
    verifyNoMoreInteractions(generator);
    assertThat(controller.create(new SampleController.Input("RANDOM", false)))
        .containsEntry("status", "LOG_GENERATED");
    when(generator.generate(null, false, "MANUAL")).thenThrow(new IllegalArgumentException());
    assertThatThrownBy(() -> controller.create(new SampleController.Input(null, false)))
        .isInstanceOf(ResponseStatusException.class);
    controller.automatic(new SampleController.Toggle(true));
    controller.tick();
    verify(generator, times(2)).random("AUTO");
  }
}
