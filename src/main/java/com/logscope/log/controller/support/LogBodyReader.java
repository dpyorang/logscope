package com.logscope.log.controller.support;

import com.logscope.log.exception.LogApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 수집 요청을 크기·인코딩 제한 안에서 엄격하게 읽는다. */
@Component
public class LogBodyReader {
  private final JsonMapper parser =
      JsonMapper.builder()
          .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  /** Content-Type·인코딩·본문 크기를 확인한 뒤 중복 키 없는 UTF-8 JSON을 읽는다. */
  public tools.jackson.databind.JsonNode read(HttpServletRequest request, int limit)
      throws IOException {
    try {
      var contentType =
          MediaType.parseMediaType(
              request.getContentType() == null ? "" : request.getContentType());
      if (!contentType.getType().equalsIgnoreCase("application")
          || !contentType.getSubtype().equalsIgnoreCase("json")
          || (contentType.getCharset() != null
              && !contentType.getCharset().equals(StandardCharsets.UTF_8)))
        throw new LogApiException(415, "UNSUPPORTED_MEDIA_TYPE");
    } catch (org.springframework.http.InvalidMediaTypeException e) {
      throw new LogApiException(415, "UNSUPPORTED_MEDIA_TYPE");
    }
    if (request.getHeader("Content-Encoding") != null
        && !request.getHeader("Content-Encoding").equalsIgnoreCase("identity"))
      throw new LogApiException(415, "UNSUPPORTED_MEDIA_TYPE");
    byte[] body = request.getInputStream().readNBytes(limit + 1);
    if (body.length > limit) throw new LogApiException(413, "PAYLOAD_TOO_LARGE");
    try {
      String utf8 =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body))
              .toString();
      return parser.readTree(utf8);
    } catch (tools.jackson.core.JacksonException | CharacterCodingException e) {
      throw new LogApiException(400, "MALFORMED_JSON");
    }
  }
}
