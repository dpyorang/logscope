package com.logscope;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 중앙 로그 수집·조회 서버의 실행 진입점이다. */
@SpringBootApplication
public class LogscopeApplication {

  /** 중앙 로그 서버를 시작한다. */
  public static void main(String[] args) {
    SpringApplication.run(LogscopeApplication.class, args);
  }
}
