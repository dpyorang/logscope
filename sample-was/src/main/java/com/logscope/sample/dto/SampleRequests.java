package com.logscope.sample.dto;

/** 샘플 전용 HTTP 요청 값을 정의한다. */
public final class SampleRequests {
  /** 직접 생성을 막고 정적 변환만 사용한다. */
  private SampleRequests() {}

  public record Input(String level, boolean exception, String scenario) {
    /** 직접 생성을 막고 정적 변환만 사용한다. */
    public Input(String level, boolean exception) {
      this(level, exception, null);
    }
  }

  public record Toggle(boolean enabled) {}
}
