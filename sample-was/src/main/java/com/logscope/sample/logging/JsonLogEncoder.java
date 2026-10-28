package com.logscope.sample.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.encoder.EncoderBase;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import tools.jackson.databind.json.JsonMapper;

/** 전용 Logback 이벤트를 공통 JSON Lines 형식으로 인코딩한다. */
public class JsonLogEncoder extends EncoderBase<ILoggingEvent> {
  private final JsonMapper json = JsonMapper.builder().build();
  private String instanceId;

  /** 파일에 기록할 샘플 서버 식별자를 Logback 설정에서 받는다. */
  public void setInstanceId(String value) {
    this.instanceId = value;
  }

  /** JSON Lines 앞에 별도 헤더를 기록하지 않는다. */
  @Override
  public byte[] headerBytes() {
    return new byte[0];
  }

  /** JSON Lines 뒤에 별도 푸터를 기록하지 않는다. */
  @Override
  public byte[] footerBytes() {
    return new byte[0];
  }

  /** 공통 로그 필드와 줄바꿈이 escape된 예외를 UTF-8 JSON 한 행으로 직렬화한다. */
  @Override
  public byte[] encode(ILoggingEvent event) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("eventId", event.getMDCPropertyMap().get("eventId"));
    fields.put(
        "occurredAt",
        new DateTimeFormatterBuilder()
            .appendInstant(3)
            .toFormatter()
            .format(Instant.ofEpochMilli(event.getTimeStamp())));
    fields.put("serviceName", "sample-was");
    fields.put("instanceId", instanceId);
    fields.put("level", event.getLevel().toString());
    fields.put("message", event.getFormattedMessage());
    fields.put(
        "exception",
        event.getThrowableProxy() == null
            ? null
            : ThrowableProxyUtil.asString(event.getThrowableProxy()));
    return (json.writeValueAsString(fields) + "\n").getBytes(StandardCharsets.UTF_8);
  }
}
