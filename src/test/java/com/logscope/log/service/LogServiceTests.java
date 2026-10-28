package com.logscope.log.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.logscope.log.domain.LogEvent;
import com.logscope.log.exception.LogApiException;
import com.logscope.log.repository.LogRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.json.JsonMapper;

class LogServiceTests {
  private static final String ID = "00000000-0000-4000-8000-000000000001";
  private final LogEvent event =
      new LogEvent(
          ID, Instant.parse("2026-10-28T00:00:00Z"), "sample-was", "was-1", "INFO", "합성 로그", null);

  @Test
  void distinguishesStoredAndDuplicateWithoutChangingOriginalEvent() {
    var repository = mock(LogRepository.class);
    when(repository.insert(event)).thenReturn(true, false);
    var service = new LogService(repository);
    var body = JsonMapper.builder().build().valueToTree(event.detail());
    assertThat(service.collect(body).status()).isEqualTo("STORED");
    assertThat(service.collect(body).status()).isEqualTo("DUPLICATE");
    verify(repository, times(2)).insert(event);
  }

  @Test
  void invalidInputDoesNotReachRepositoryAndDetailsKeepOriginalException() {
    var repository = mock(LogRepository.class);
    var service = new LogService(repository);
    assertThatThrownBy(() -> service.collect(null)).isInstanceOf(LogApiException.class);
    assertThatThrownBy(() -> service.detail("invalid")).isInstanceOf(LogApiException.class);
    verifyNoInteractions(repository);
    var failed =
        new LogEvent(ID, event.occurredAt(), "sample-was", "was-1", "ERROR", "합성 결제 오류", "합성 원인");
    when(repository.find(ID)).thenReturn(failed);
    assertThat(service.detail(ID).exception()).isEqualTo("합성 원인");
  }

  @Test
  void listKeepsUnsupportedFiltersAndPaginationErrorsInsteadOfTruncatingSilently() {
    var repository = mock(LogRepository.class);
    var service = new LogService(repository);
    var params = new LinkedMultiValueMap<String, String>();
    params.add("from", "2026-10-28T00:00:00Z");
    params.add("to", "2026-10-29T00:00:00Z");
    params.add("size", "1");
    params.add("level", "ERROR");
    assertThatThrownBy(() -> service.list(params)).hasMessage("QUERY_FEATURE_NOT_IMPLEMENTED");
    verifyNoInteractions(repository);
    params.remove("level");
    when(repository.list(any(), any(), eq(1))).thenReturn(List.of(event, event));
    assertThatThrownBy(() -> service.list(params)).hasMessage("PAGINATION_NOT_IMPLEMENTED");
    when(repository.list(any(), any(), eq(1))).thenReturn(List.of(event));
    assertThat(service.list(params).items()).containsExactly(event.summary());
  }
}
