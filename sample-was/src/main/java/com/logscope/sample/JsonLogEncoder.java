package com.logscope.sample;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.encoder.EncoderBase;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import tools.jackson.databind.json.JsonMapper;

public class JsonLogEncoder extends EncoderBase<ILoggingEvent> {
  private final JsonMapper json = JsonMapper.builder().build();
  private String instanceId;

  public void setInstanceId(String value) {
    this.instanceId = value;
  }

  @Override
  public byte[] headerBytes() {
    return new byte[0];
  }

  @Override
  public byte[] footerBytes() {
    return new byte[0];
  }

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
