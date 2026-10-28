package com.logscope.log.config;

import com.logscope.log.service.LogSearchIndexer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 설정으로 허용된 ES 색인 스케줄을 활성화한다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    name = "logscope.search.scheduling",
    havingValue = "true",
    matchIfMissing = true)
public class LogSearchSchedulingConfig {
  private final LogSearchIndexer indexer;

  /** 정해진 주기로 실행할 색인 작업자를 연결한다. */
  public LogSearchSchedulingConfig(LogSearchIndexer indexer) {
    this.indexer = indexer;
  }

  /** 색인 작업을 1초 fixed-delay로 호출하며 실행 허용 여부는 설정을 따른다. */
  @Scheduled(fixedDelay = 1000)
  public void synchronize() {
    indexer.runCycle();
  }
}
