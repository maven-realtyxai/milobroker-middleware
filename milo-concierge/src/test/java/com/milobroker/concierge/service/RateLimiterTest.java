package com.milobroker.concierge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
  private final AtomicReference<Instant> now =
    new AtomicReference<>(Instant.parse("2026-11-05T00:00:00Z"));
  private final RateLimiterService limiter = new RateLimiterService(3, 1);

  private void setup() {
    limiter.setClock(Clock.fixed(now.get(), ZoneOffset.UTC));
  }

  private void advanceMinutes(long minutes) {
    now.set(now.get().plusSeconds(minutes * 60));
    limiter.setClock(Clock.fixed(now.get(), ZoneOffset.UTC));
  }

  @Test
  void allowsUpToCapThenNotifiesThenDropsSilently() {
    setup();
    assertEquals(RateLimiterService.Result.ALLOWED, limiter.tryAcquire("9198"));
    assertEquals(RateLimiterService.Result.ALLOWED, limiter.tryAcquire("9198"));
    assertEquals(RateLimiterService.Result.ALLOWED, limiter.tryAcquire("9198"));
    assertEquals(RateLimiterService.Result.NOTIFY_AND_DROP, limiter.tryAcquire("9198"));
    assertEquals(RateLimiterService.Result.DROP_SILENTLY, limiter.tryAcquire("9198"));
    assertEquals(RateLimiterService.Result.DROP_SILENTLY, limiter.tryAcquire("9198"));
  }

  @Test
  void windowExpiryResetsTheSender() {
    setup();
    limiter.tryAcquire("9198");
    limiter.tryAcquire("9198");
    limiter.tryAcquire("9198");
    limiter.tryAcquire("9198");
    advanceMinutes(61);
    assertEquals(RateLimiterService.Result.ALLOWED, limiter.tryAcquire("9198"));
  }

  @Test
  void sendersAreTrackedIndependently() {
    setup();
    limiter.tryAcquire("9198");
    limiter.tryAcquire("9198");
    limiter.tryAcquire("9198");
    assertEquals(RateLimiterService.Result.NOTIFY_AND_DROP, limiter.tryAcquire("9198"));
    assertEquals(RateLimiterService.Result.ALLOWED, limiter.tryAcquire("9111"));
  }

  @Test
  void blankKeyIsAlwaysAllowed() {
    setup();
    for (int i = 0; i < 10; i++) {
      assertEquals(RateLimiterService.Result.ALLOWED, limiter.tryAcquire(" "));
    }
  }
}
