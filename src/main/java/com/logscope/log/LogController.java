package com.logscope.log;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/logs")
class LogController {
  private static final int MAX_BODY = 262144;
  private final JsonMapper parser =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final LogStore store;

  LogController(LogStore store) {
    this.store = store;
  }

  @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Receipt> collect(HttpServletRequest request) throws IOException {
    LogEvent event = LogInput.parse(read(request, MAX_BODY));
    boolean inserted = store.insert(event);
    Receipt receipt = new Receipt(event.eventId(), inserted ? "STORED" : "DUPLICATE");
    return inserted
        ? ResponseEntity.created(URI.create("/api/v1/logs/" + event.eventId())).body(receipt)
        : ResponseEntity.ok(receipt);
  }

  @PostMapping(value = "/batch", produces = MediaType.APPLICATION_JSON_VALUE)
  BatchReceipt collectBatch(HttpServletRequest request) throws IOException {
    var body = read(request, 1048576);
    if (body == null || !body.isArray() || body.isEmpty() || body.size() > 100)
      throw new LogApiException("body", "OUT_OF_RANGE");
    int stored = 0;
    int duplicates = 0;
    var rejected = new java.util.ArrayList<Rejected>();
    for (int i = 0; i < body.size(); i++) {
      try {
        if (parser.writeValueAsBytes(body.get(i)).length > MAX_BODY)
          throw new LogApiException(413, "PAYLOAD_TOO_LARGE");
        if (store.insert(LogInput.parse(body.get(i)))) stored++;
        else duplicates++;
      } catch (LogApiException error) {
        rejected.add(new Rejected(i, error.code, error.errors));
      }
    }
    if (!rejected.isEmpty())
      org.slf4j.LoggerFactory.getLogger(LogController.class)
          .warn("Batch rejected {} items", rejected.size());
    return new BatchReceipt(stored, duplicates, rejected);
  }

  private tools.jackson.databind.JsonNode read(HttpServletRequest request, int limit)
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

  record Rejected(int index, String code, List<LogApiException.FieldError> errors) {}

  record BatchReceipt(int stored, int duplicates, List<Rejected> rejected) {}

  @GetMapping(value = "/{eventId}", produces = MediaType.APPLICATION_JSON_VALUE)
  LogEvent.Detail detail(@PathVariable String eventId) {
    return store.find(LogInput.uuid(eventId)).detail();
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  Listing list(@RequestParam MultiValueMap<String, String> params) {
    for (String key : params.keySet()) {
      if (!Set.of("from", "to", "size", "instanceId", "level", "keyword", "cursor").contains(key))
        throw new LogApiException("query", "UNKNOWN_FIELD");
      if (params.get(key).size() != 1) throw new LogApiException(key, "DUPLICATE_PARAMETER");
      if (params.getFirst(key).isBlank()) throw new LogApiException(key, "INVALID_FORMAT");
    }
    var from = LogInput.time(params.getFirst("from"), "from");
    var to = LogInput.time(params.getFirst("to"), "to");
    if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofHours(24)) > 0)
      throw new LogApiException("to", "OUT_OF_RANGE");
    int size = 50;
    if (params.containsKey("size")) {
      try {
        size = Integer.parseInt(params.getFirst("size"));
      } catch (NumberFormatException e) {
        throw new LogApiException("size", "INVALID_FORMAT");
      }
      if (size < 1 || size > 100) throw new LogApiException("size", "OUT_OF_RANGE");
    }
    for (String pending : List.of("instanceId", "level", "keyword", "cursor")) {
      if (params.containsKey(pending))
        throw new LogApiException(501, "QUERY_FEATURE_NOT_IMPLEMENTED");
    }
    var rows = store.list(from, to, size);
    if (rows.size() > size) throw new LogApiException(501, "PAGINATION_NOT_IMPLEMENTED");
    return new Listing(rows.stream().map(LogEvent::summary).toList(), size, null, null);
  }

  record Receipt(String eventId, String status) {}

  record Listing(
      List<LogEvent.Summary> items, int size, String nextCursor, String previousCursor) {}
}
