package com.logscope.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 독립 샘플 WAS의 실행 진입점이다. */
@SpringBootApplication
public class SampleApplication {
  /** 독립 샘플 WAS를 시작한다. */
  public static void main(String[] args) {
    SpringApplication.run(SampleApplication.class, args);
  }
}
