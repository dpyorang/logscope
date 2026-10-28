package com.logscope.log;

import org.springframework.stereotype.Component;

@Component
class LogStreamSignal {
  private long version;

  synchronized long version() {
    return version;
  }

  synchronized void changed() {
    version++;
    notifyAll();
  }

  synchronized void await(long observed, long millis) throws InterruptedException {
    if (version == observed) wait(millis);
  }
}
