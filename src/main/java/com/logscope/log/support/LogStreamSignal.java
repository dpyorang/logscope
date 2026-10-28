package com.logscope.log.support;

import org.springframework.stereotype.Component;

/** 저장 commit을 SSE 작업자에게 알리는 본문 없는 프로세스 내 신호다. */
@Component
public class LogStreamSignal {
  private long version;

  /** DB 조회 전 깨우기 신호의 버전을 관측한다. */
  public synchronized long version() {
    return version;
  }

  /** 저장 commit 이후 신호 버전을 올리고 대기 작업자를 깨운다. */
  public synchronized void changed() {
    version++;
    notifyAll();
  }

  /** 조회 이후 새 신호가 없을 때만 제한 시간 동안 대기한다. */
  public synchronized void await(long observed, long millis) throws InterruptedException {
    if (version == observed) wait(millis);
  }
}
