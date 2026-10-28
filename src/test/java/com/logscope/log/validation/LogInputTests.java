package com.logscope.log.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logscope.log.domain.LogEvent;
import com.logscope.log.exception.LogApiException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class LogInputTests {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ID = "7b2a8c30-8d94-4d5f-a6d8-931cf6a6a101";

  private ObjectNode valid() {
    return JSON.createObjectNode()
        .put("eventId", ID)
        .put("occurredAt", "2026-10-27T03:00:00.123Z")
        .put("serviceName", "sample-was")
        .put("instanceId", "was-2")
        .put("level", "ERROR")
        .put("message", " A synthetic message\n ");
  }

  private void rejects(Runnable action, String field, String code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            LogApiException.class,
            error -> {
              assertThat(error.status()).isEqualTo(400);
              assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
              assertThat(error.errors())
                  .containsExactly(new LogApiException.FieldError(field, code));
            });
  }

  @Test
  void preservesFieldsWhitespaceAndTrace() {
    var node = valid().put("exception", "SyntheticException\n\tat sample.run(Sample.java:1)");
    var result = LogInput.parse(node);
    assertThat(result)
        .isEqualTo(
            new LogEvent(
                ID,
                Instant.parse("2026-10-27T03:00:00.123Z"),
                "sample-was",
                "was-2",
                "ERROR",
                " A synthetic message\n ",
                "SyntheticException\n\tat sample.run(Sample.java:1)"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"eventId", "occurredAt", "serviceName", "instanceId", "level", "message"})
  void rejectsMissingNullBlankAndNonStringRequiredFields(String field) {
    var missing = valid();
    missing.remove(field);
    rejects(() -> LogInput.parse(missing), field, "REQUIRED");
    rejects(() -> LogInput.parse(valid().putNull(field)), field, "REQUIRED");
    rejects(() -> LogInput.parse(valid().put(field, " \t\n")), field, "REQUIRED");
    rejects(() -> LogInput.parse(valid().put(field, 7)), field, "INVALID_FORMAT");
    rejects(() -> LogInput.parse(valid().put(field, true)), field, "INVALID_FORMAT");
  }

  @Test
  void rejectsNonObjectBodyAndUnknownFields() {
    for (JsonNode node :
        new JsonNode[] {
          null, JSON.nullNode(), JSON.createArrayNode(), JSON.getNodeFactory().textNode("A")
        }) rejects(() -> LogInput.parse(node), "body", "INVALID_FORMAT");
    rejects(() -> LogInput.parse(valid().put("extra", "synthetic")), "body", "UNKNOWN_FIELD");
  }

  @Test
  void absentAndNullExceptionAreEquivalent() {
    assertThat(LogInput.parse(valid()).exception()).isNull();
    assertThat(LogInput.parse(valid().putNull("exception"))).isEqualTo(LogInput.parse(valid()));
    rejects(() -> LogInput.parse(valid().put("exception", "")), "exception", "REQUIRED");
    rejects(() -> LogInput.parse(valid().put("exception", false)), "exception", "INVALID_FORMAT");
  }

  @ParameterizedTest
  @ValueSource(strings = {"TRACE", "DEBUG", "INFO", "WARN", "ERROR"})
  void acceptsAllSpecifiedLevelsWithOrWithoutException(String level) {
    assertThat(LogInput.parse(valid().put("level", level)).level()).isEqualTo(level);
    assertThat(
            LogInput.parse(valid().put("level", level).put("exception", "SyntheticException"))
                .exception())
        .isEqualTo("SyntheticException");
  }

  @ParameterizedTest
  @ValueSource(strings = {"error", "FATAL", "INFO ", "A"})
  void rejectsUnknownOrNonCanonicalLevels(String level) {
    rejects(() -> LogInput.parse(valid().put("level", level)), "level", "INVALID_ENUM");
  }

  @ParameterizedTest
  @ValueSource(strings = {"serviceName", "instanceId"})
  void enforcesIdentifierPatternAndLength(String field) {
    for (String value : List.of("a", "a".repeat(64), "sample-was_2.app"))
      assertThat(LogInput.parse(valid().put(field, value))).isNotNull();
    for (String value : List.of("a".repeat(65), "-sample", "Sample", "was 2", "서버"))
      rejects(() -> LogInput.parse(valid().put(field, value)), field, "INVALID_FORMAT");
  }

  @ParameterizedTest
  @ValueSource(strings = {"message", "exception"})
  void countsUnicodeCodePointsAndRejectsUnpairedSurrogates(String field) {
    int max = field.equals("message") ? 4096 : 32768;
    for (String unit : List.of("a", "가", "😀")) {
      assertThat(LogInput.parse(valid().put(field, unit.repeat(max)))).isNotNull();
      rejects(() -> LogInput.parse(valid().put(field, unit.repeat(max + 1))), field, "TOO_LONG");
    }
    for (String value : new String[] {"\uD800", "\uDC00", "\uD800A", "A\uDC00"})
      rejects(() -> LogInput.parse(valid().put(field, value)), field, "INVALID_FORMAT");
  }

  @Test
  void uuidRequiresCanonicalLowercaseVersionFourAndVariant() {
    assertThat(LogInput.uuid(ID)).isEqualTo(ID);
    for (String id :
        new String[] {
          null,
          "A",
          ID.toUpperCase(),
          ID.replace("4d5f", "1d5f"),
          ID.replace("a6d8", "76d8"),
          ID.replace("-", ""),
          ID + " "
        }) rejects(() -> LogInput.uuid(id), "eventId", "INVALID_FORMAT");
  }

  static Stream<Arguments> validTimes() {
    return Stream.of(
        Arguments.of("2026-10-27T03:00:00Z", "2026-10-27T03:00:00Z"),
        Arguments.of("2026-10-27T03:00:00.1Z", "2026-10-27T03:00:00.100Z"),
        Arguments.of("2026-10-27T03:00:00.12Z", "2026-10-27T03:00:00.120Z"),
        Arguments.of("2026-10-27T03:00:00.123Z", "2026-10-27T03:00:00.123Z"),
        Arguments.of("2024-02-29T00:00:00Z", "2024-02-29T00:00:00Z"),
        Arguments.of("1000-01-01T00:00:00Z", "1000-01-01T00:00:00Z"),
        Arguments.of("9999-12-31T23:59:59.999Z", "9999-12-31T23:59:59.999Z"));
  }

  @ParameterizedTest
  @MethodSource("validTimes")
  void parsesUtcPrecisionAndStorageBoundaries(String input, String expected) {
    assertThat(LogInput.time(input, "from")).isEqualTo(Instant.parse(expected));
    assertThat(LogInput.parse(valid().put("occurredAt", input)).occurredAt())
        .isEqualTo(Instant.parse(expected));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "A",
        "2026-02-29T00:00:00Z",
        "2026-02-30T00:00:00Z",
        "2026-13-01T00:00:00Z",
        "2026-10-27T24:00:00Z",
        "2026-10-27T00:00:60Z",
        "2026-10-27T00:00:00+00:00",
        "2026-10-27T00:00:00.1234Z",
        "2026-10-27t00:00:00z"
      })
  void rejectsInvalidCalendarOrUtcFormatAndRetainsFieldName(String input) {
    rejects(() -> LogInput.time(input, "to"), "to", "INVALID_UTC_TIMESTAMP");
  }

  @Test
  void distinguishesMissingTimeAndStorageRangeErrors() {
    rejects(() -> LogInput.time(null, "from"), "from", "REQUIRED");
    rejects(
        () -> LogInput.time("0999-12-31T23:59:59Z", "occurredAt"), "occurredAt", "OUT_OF_RANGE");
  }
}
