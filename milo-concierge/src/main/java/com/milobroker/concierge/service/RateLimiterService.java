package com.milobroker.concierge.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Per-sender flood control for the WhatsApp webhook. Each sender gets
 * {@code maxPerWindow} messages per rolling window; the first message past
 * the cap earns one pause notice, the rest of the window is dropped silently
 * so a looping client cannot wedge the conversation pipeline. Backed by
 * in-memory sliding windows, which is enough for a single concierge replica:
 * a restart simply resets the counters.
 */
@Service
public class RateLimiterService {
  public enum Result { ALLOWED, NOTIFY_AND_DROP, DROP_SILENTLY }

  private final int maxPerWindow;
  private final Duration window;
  private Clock clock = Clock.systemUTC();
  private final ConcurrentHashMap<String, ArrayDeque<Instant>> hits = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Instant> notified = new ConcurrentHashMap<>();

  public RateLimiterService(
      @Value("${milobroker.chat.rate-limit-max:25}") int maxPerWindow,
      @Value("${milobroker.chat.rate-limit-window-hours:1}") long windowHours) {
    this.maxPerWindow = maxPerWindow;
    this.window = Duration.ofHours(Math.max(windowHours, 1));
  }

  /** Test hook: drive the window with a fake clock. */
  void setClock(Clock clock) {
    this.clock = clock;
  }

  public int maxPerWindow() {
    return maxPerWindow;
  }

  public Result tryAcquire(String key) {
    if (key == null || key.isBlank()) {
      return Result.ALLOWED;
    }
    Instant now = clock.instant();
    Instant cutoff = now.minus(window);
    ArrayDeque<Instant> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
    synchronized (deque) {
      while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
        deque.pollFirst();
      }
      if (deque.size() < maxPerWindow) {
        deque.addLast(now);
        return Result.ALLOWED;
      }
    }
    Instant lastNotice = notified.get(key);
    if (lastNotice == null || lastNotice.isBefore(cutoff)) {
      notified.put(key, now);
      return Result.NOTIFY_AND_DROP;
    }
    return Result.DROP_SILENTLY;
  }
}
