package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.PositionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events broadcaster for UI refresh triggers.
 *
 * <p>Emits a {@code batch-update} event whenever the latest OpenSky batch epoch changes in Redis.
 * A lightweight heartbeat is also emitted periodically to keep connections active through proxies.
 */
@Service
public class FlightUpdateStreamService {
  private static final Logger log = LoggerFactory.getLogger(FlightUpdateStreamService.class);
  private static final long STREAM_TIMEOUT_MS = 0L;
  private static final long POLL_INTERVAL_MS = 2_000L;
  private static final long HEARTBEAT_INTERVAL_MS = 15_000L;
  private static final int REDIS_SCAN_COUNT = 512;

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final DashboardProperties properties;
  private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dashboard-flight-stream");
        thread.setDaemon(true);
        return thread;
      });

  private volatile Long lastBroadcastBatchEpoch = null;
  private volatile long lastHeartbeatAtMs = 0L;
  private volatile boolean started = false;

  public FlightUpdateStreamService(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      DashboardProperties properties) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  /**
   * Opens an SSE stream and registers lifecycle callbacks.
   *
   * @return configured emitter ready for streaming events
   */
  public SseEmitter openStream() {
    startIfNeeded();

    SseEmitter emitter = createEmitter();
    emitters.add(emitter);

    emitter.onCompletion(() -> emitters.remove(emitter));
    emitter.onTimeout(() -> emitters.remove(emitter));
    emitter.onError(ex -> emitters.remove(emitter));

    Long latestBatchEpoch = latestBatchEpoch();
    sendEvent(emitter, "connected", payload(latestBatchEpoch));
    if (latestBatchEpoch != null) {
      sendEvent(emitter, "batch-update", payload(latestBatchEpoch));
    }

    return emitter;
  }

  SseEmitter createEmitter() {
    return new SseEmitter(STREAM_TIMEOUT_MS);
  }

  private synchronized void startIfNeeded() {
    if (started) {
      return;
    }
    started = true;
    scheduler.scheduleWithFixedDelay(this::pollAndBroadcast, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
  }

  private void pollAndBroadcast() {
    try {
      Long latestBatchEpoch = latestBatchEpoch();
      if (latestBatchEpoch != null && !Objects.equals(latestBatchEpoch, lastBroadcastBatchEpoch)) {
        lastBroadcastBatchEpoch = latestBatchEpoch;
        broadcast("batch-update", payload(latestBatchEpoch));
      }

      long now = System.currentTimeMillis();
      if (now - lastHeartbeatAtMs >= HEARTBEAT_INTERVAL_MS) {
        lastHeartbeatAtMs = now;
        broadcast("heartbeat", payload(lastBroadcastBatchEpoch));
      }
    } catch (Exception ex) {
      log.warn("Flight update stream polling failed", ex);
    }
  }

  private void broadcast(String eventName, Map<String, Object> payload) {
    for (SseEmitter emitter : emitters) {
      sendEvent(emitter, eventName, payload);
    }
  }

  private void sendEvent(SseEmitter emitter, String eventName, Map<String, Object> payload) {
    try {
      emitter.send(SseEmitter.event().name(eventName).data(payload));
    } catch (Exception ex) {
      emitters.remove(emitter);
      if (isExpectedClientDisconnect(ex)) {
        log.debug("SSE client disconnected during {} delivery: {}", eventName, rootCauseSummary(ex));
        completeSilently(emitter);
        return;
      }
      log.warn("SSE event delivery failed for event={}", eventName, ex);
      completeWithErrorSilently(emitter, ex);
    }
  }

  static boolean isExpectedClientDisconnect(Throwable error) {
    Throwable current = error;
    while (current != null) {
      String className = current.getClass().getName();
      if (className.endsWith("ClientAbortException") || className.endsWith("EofException")) {
        return true;
      }
      if (hasDisconnectMessage(current.getMessage())) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static boolean hasDisconnectMessage(String message) {
    if (message == null || message.isBlank()) {
      return false;
    }
    String normalized = message.toLowerCase(Locale.ROOT);
    return normalized.contains("broken pipe")
        || normalized.contains("connection reset")
        || normalized.contains("socket closed")
        || normalized.contains("stream closed")
        || normalized.contains("connection abort")
        || normalized.contains("forcibly closed by the remote host");
  }

  private static String rootCauseSummary(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    String message = current.getMessage();
    if (message == null || message.isBlank()) {
      return current.getClass().getSimpleName();
    }
    return current.getClass().getSimpleName() + ": " + message;
  }

  private static void completeSilently(SseEmitter emitter) {
    try {
      emitter.complete();
    } catch (Exception ignore) {
      // Emitter is already closed/broken; nothing else to do.
    }
  }

  private static void completeWithErrorSilently(SseEmitter emitter, Exception error) {
    try {
      emitter.completeWithError(error);
    } catch (Exception ignore) {
      // Emitter is already closed/broken; nothing else to do.
    }
  }

  private Map<String, Object> payload(Long latestBatchEpoch) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("latestOpenSkyBatchEpoch", latestBatchEpoch);
    payload.put("timestamp", Instant.now().toString());
    return payload;
  }

  private Long latestBatchEpoch() {
    HashOperations<String, Object, Object> hashOps = redisTemplate.opsForHash();
    ScanOptions scanOptions = ScanOptions.scanOptions().count(REDIS_SCAN_COUNT).build();
    Long latest = null;

    try (Cursor<Map.Entry<Object, Object>> cursor = hashOps.scan(properties.getRedis().getLastPositionsKey(), scanOptions)) {
      while (cursor.hasNext()) {
        Map.Entry<Object, Object> entry = cursor.next();
        Object payloadObj = entry.getValue();
        if (payloadObj == null) {
          continue;
        }

        PositionEvent event = parseEvent(payloadObj.toString());
        if (event == null || event.openskyFetchEpoch() == null) {
          continue;
        }

        long epoch = event.openskyFetchEpoch();
        if (latest == null || epoch > latest) {
          latest = epoch;
        }
      }
    }

    return latest;
  }

  private PositionEvent parseEvent(String payload) {
    try {
      return objectMapper.readValue(payload, PositionEvent.class);
    } catch (Exception ex) {
      return null;
    }
  }
}
