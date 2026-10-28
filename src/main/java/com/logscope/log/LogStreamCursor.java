package com.logscope.log;

record LogStreamCursor(String epoch, long sequence) {
  static LogStreamCursor parse(String value) {
    if (value == null
        || value.length() > 56
        || !value.matches(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:(0|[1-9][0-9]{0,18})"))
      throw new LogApiException("after", "INVALID_FORMAT");
    try {
      int colon = value.indexOf(':');
      return new LogStreamCursor(
          value.substring(0, colon), Long.parseLong(value.substring(colon + 1)));
    } catch (NumberFormatException error) {
      throw new LogApiException("after", "OUT_OF_RANGE");
    }
  }

  void validate(LogStreamCursor head) {
    if (!epoch.equals(head.epoch)) throw new LogApiException(409, "LIVE_CURSOR_INVALID");
    if (sequence > head.sequence) throw new LogApiException(409, "LIVE_CURSOR_AHEAD");
  }

  @Override
  public String toString() {
    return epoch + ":" + sequence;
  }
}
