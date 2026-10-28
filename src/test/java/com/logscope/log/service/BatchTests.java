package com.logscope.log.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.logscope.log.controller.LogController;
import com.logscope.log.controller.support.LogBodyReader;
import com.logscope.log.exception.LogApiException;
import com.logscope.log.repository.LogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;

class BatchTests {
  private static final String EVENT =
      """
      {"eventId":"00000000-0000-4000-8000-000000000001","occurredAt":"2026-10-27T00:00:00Z","serviceName":"sample-was","instanceId":"was-1","level":"INFO","message":"Synthetic"}
      """;

  private MockHttpServletRequest request(String body) {
    var request = new MockHttpServletRequest();
    request.setContentType("application/json");
    request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return request;
  }

  @Test
  void countsStoredDuplicatesAndRejectedItems() throws Exception {
    var store = mock(LogRepository.class);
    when(store.insert(any())).thenReturn(true, false);
    var receipt =
        new LogController(new LogService(store), new LogBodyReader())
            .collectBatch(request("[" + EVENT + ",{}," + EVENT + "]"));
    assertThat(receipt.stored()).isEqualTo(1);
    assertThat(receipt.duplicates()).isEqualTo(1);
    assertThat(receipt.rejected()).hasSize(1);
    assertThat(receipt.rejected().getFirst().index()).isEqualTo(1);
    verify(store, times(2)).insert(any());
  }

  @Test
  void rejectsOversizedItemAndContinuesWithValidEvent() throws Exception {
    var store = mock(LogRepository.class);
    when(store.insert(any())).thenReturn(true);
    String huge = EVENT.replace("Synthetic", "a".repeat(262144));
    var result =
        new LogController(new LogService(store), new LogBodyReader())
            .collectBatch(request("[" + huge + "," + EVENT + "]"));
    assertThat(result.stored()).isEqualTo(1);
    assertThat(result.rejected().getFirst().code()).isEqualTo("PAYLOAD_TOO_LARGE");
    verify(store).insert(any());
  }

  @Test
  void propagatesTransientStoreFailureSoCollectorCanRetry() {
    var store = mock(LogRepository.class);
    when(store.insert(any())).thenThrow(new DataAccessResourceFailureException("Synthetic outage"));
    assertThatThrownBy(
            () ->
                new LogController(new LogService(store), new LogBodyReader())
                    .collectBatch(request("[" + EVENT + "]")))
        .isInstanceOf(DataAccessResourceFailureException.class);
  }

  @Test
  void rejectsBadEnvelopesBeforeCallingStore() {
    var store = mock(LogRepository.class);
    for (String body :
        java.util.List.of("[]", "{}", "null", "[{}] trailing", "[" + "{},".repeat(100) + "{}]"))
      assertThatThrownBy(
              () ->
                  new LogController(new LogService(store), new LogBodyReader())
                      .collectBatch(request(body)))
          .isInstanceOf(LogApiException.class);
    verifyNoInteractions(store);
  }
}
