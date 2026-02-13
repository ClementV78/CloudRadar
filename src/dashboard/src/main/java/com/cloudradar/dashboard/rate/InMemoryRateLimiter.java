package com.cloudradar.dashboard.rate;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Thread-safe in-memory sliding-window rate limiter.
 *
 * <p>This implementation is lightweight and intentionally local to one application instance.
 */
@Component
public class InMemoryRateLimiter {
  private final Map<String, Deque<Long>> eventsByClient = new ConcurrentHashMap<>();

  /**
   * Checks whether a request is allowed for a given client key.
   *
   * @param clientKey caller identity key (IP or forwarded IP)
   * @param windowSeconds window size in seconds
   * @param maxRequests maximum requests allowed in the window
   * @return {@code true} when request can proceed, {@code false} otherwise
   */
  public boolean allow(String clientKey, int windowSeconds, int maxRequests) {
    long now = Instant.now().getEpochSecond();
    long cutoff = now - Math.max(1, windowSeconds);

    Deque<Long> deque = eventsByClient.computeIfAbsent(clientKey, ignored -> new ArrayDeque<>());
    synchronized (deque) {
      while (!deque.isEmpty() && deque.peekFirst() < cutoff) {
        deque.pollFirst();
      }
      if (deque.size() >= Math.max(1, maxRequests)) {
        return false;
      }
      deque.addLast(now);
      return true;
    }
  }
}
