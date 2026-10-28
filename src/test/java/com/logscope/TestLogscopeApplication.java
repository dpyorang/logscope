package com.logscope;

import com.logscope.config.TestcontainersConfiguration;
import org.springframework.boot.SpringApplication;

public class TestLogscopeApplication {

  public static void main(String[] args) {
    SpringApplication.from(LogscopeApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
