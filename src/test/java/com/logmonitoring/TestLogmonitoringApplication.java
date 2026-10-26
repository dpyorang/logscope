package com.logmonitoring;

import org.springframework.boot.SpringApplication;

public class TestLogmonitoringApplication {

  public static void main(String[] args) {
    SpringApplication.from(LogmonitoringApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
