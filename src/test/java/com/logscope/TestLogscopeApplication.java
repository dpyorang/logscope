package com.logscope;

import org.springframework.boot.SpringApplication;

public class TestLogscopeApplication {

  public static void main(String[] args) {
    SpringApplication.from(LogscopeApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
