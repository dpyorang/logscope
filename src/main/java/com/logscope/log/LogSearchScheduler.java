package com.logscope.log;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    name = "logscope.search.scheduling",
    havingValue = "true",
    matchIfMissing = true)
class LogSearchScheduler {
  private final LogSearchIndexer indexer;

  LogSearchScheduler(LogSearchIndexer indexer) {
    this.indexer = indexer;
  }

  @Scheduled(fixedDelay = 1000)
  void synchronize() {
    indexer.runCycle();
  }
}
