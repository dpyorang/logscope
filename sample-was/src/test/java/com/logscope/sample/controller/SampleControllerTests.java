package com.logscope.sample.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.logscope.sample.dto.SampleRequests.Input;
import com.logscope.sample.service.SampleService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SampleControllerTests {
  @Test
  void mapsInvalidScenarioTo400WithoutLeakingBusinessExceptionDetails() {
    var service = mock(SampleService.class);
    var input = new Input("INFO", false, "UNKNOWN");
    when(service.create(input))
        .thenThrow(new IllegalArgumentException("synthetic internal reason"));
    assertThatThrownBy(() -> new SampleController(service).create(input))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode().value()).isEqualTo(400);
              assertThat(error.getReason()).isEqualTo("Invalid level or exception option");
            });
    verify(service).create(input);
  }
}
