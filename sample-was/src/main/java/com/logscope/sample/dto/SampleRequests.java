package com.logscope.sample.dto;

/** 샘플 전용 HTTP 요청 값을 정의한다. */
public final class SampleRequests {
  /** 요청 값은 내부 record로 생성하므로 묶음 클래스의 직접 생성을 막는다. */
  private SampleRequests() {}

  public record Input(String level, boolean exception, String scenario) {
    /** 기존 레벨·예외 입력을 상황 코드 없는 수동 요청으로 변환한다. */
    public Input(String level, boolean exception) {
      this(level, exception, null);
    }
  }

  public record Toggle(boolean enabled) {}
}
