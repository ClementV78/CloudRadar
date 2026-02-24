package com.cloudradar.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.cloudradar.dashboard.config.DashboardProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class FlightUpdateStreamServiceTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private HashOperations<String, Object, Object> hashOperations;

  private DashboardProperties properties;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    properties = new DashboardProperties();
    properties.getRedis().setLastPositionsKey("cloudradar:aircraft:last");
    objectMapper = new ObjectMapper();
    lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
  }

  @Test
  void openStream_expectedDisconnectCleansEmitterWithoutErrorCompletion() throws Exception {
    ScriptedEmitter emitter = ScriptedEmitter.failOnSend(2, new IOException("Broken pipe"));
    TestFlightUpdateStreamService service =
        new TestFlightUpdateStreamService(redisTemplate, objectMapper, properties, emitter);
    markStarted(service);
    mockLatestBatchEpochScan(1760000000L);

    service.openStream();

    assertEquals(2, emitter.sendCalls);
    assertTrue(emitter.completeCalled);
    assertFalse(emitter.completeWithErrorCalled);
    assertFalse(emitterSet(service).contains(emitter));
  }

  @Test
  void openStream_unexpectedFailureCompletesWithError() throws Exception {
    IllegalStateException failure = new IllegalStateException("serialization failed");
    ScriptedEmitter emitter = ScriptedEmitter.failOnSend(2, failure);
    TestFlightUpdateStreamService service =
        new TestFlightUpdateStreamService(redisTemplate, objectMapper, properties, emitter);
    markStarted(service);
    mockLatestBatchEpochScan(1760000000L);

    service.openStream();

    assertEquals(2, emitter.sendCalls);
    assertFalse(emitter.completeCalled);
    assertTrue(emitter.completeWithErrorCalled);
    assertSame(failure, emitter.completeWithErrorThrowable);
    assertFalse(emitterSet(service).contains(emitter));
  }

  @Test
  void isExpectedClientDisconnect_matchesCommonNestedMessages() {
    IOException io = new IOException("Connection reset by peer");
    RuntimeException wrapped = new RuntimeException("Async request write failed", io);

    assertTrue(FlightUpdateStreamService.isExpectedClientDisconnect(wrapped));
    assertFalse(FlightUpdateStreamService.isExpectedClientDisconnect(new IllegalStateException("boom")));
  }

  private void mockLatestBatchEpochScan(long epoch) {
    String payload = "{\"icao24\":\"abc123\",\"opensky_fetch_epoch\":" + epoch + "}";
    Cursor<Map.Entry<Object, Object>> cursor =
        new ListBackedCursor(List.of(Map.entry("abc123", payload)));
    when(hashOperations.scan(eq("cloudradar:aircraft:last"), any())).thenReturn(cursor);
  }

  private static void markStarted(FlightUpdateStreamService service) throws Exception {
    Field startedField = FlightUpdateStreamService.class.getDeclaredField("started");
    startedField.setAccessible(true);
    startedField.setBoolean(service, true);
  }

  @SuppressWarnings("unchecked")
  private static Set<SseEmitter> emitterSet(FlightUpdateStreamService service) throws Exception {
    Field emittersField = FlightUpdateStreamService.class.getDeclaredField("emitters");
    emittersField.setAccessible(true);
    return (Set<SseEmitter>) emittersField.get(service);
  }

  private static final class TestFlightUpdateStreamService extends FlightUpdateStreamService {
    private final SseEmitter emitter;

    private TestFlightUpdateStreamService(
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        DashboardProperties properties,
        SseEmitter emitter) {
      super(redisTemplate, objectMapper, properties);
      this.emitter = emitter;
    }

    @Override
    SseEmitter createEmitter() {
      return emitter;
    }
  }

  private static final class ScriptedEmitter extends SseEmitter {
    private final int failOnSend;
    private final IOException ioFailure;
    private final RuntimeException runtimeFailure;
    private int sendCalls;
    private boolean completeCalled;
    private boolean completeWithErrorCalled;
    private Throwable completeWithErrorThrowable;

    private ScriptedEmitter(int failOnSend, IOException ioFailure, RuntimeException runtimeFailure) {
      super(0L);
      this.failOnSend = failOnSend;
      this.ioFailure = ioFailure;
      this.runtimeFailure = runtimeFailure;
    }

    private static ScriptedEmitter failOnSend(int sendNumber, IOException failure) {
      return new ScriptedEmitter(sendNumber, failure, null);
    }

    private static ScriptedEmitter failOnSend(int sendNumber, RuntimeException failure) {
      return new ScriptedEmitter(sendNumber, null, failure);
    }

    @Override
    public synchronized void send(SseEventBuilder builder) throws IOException {
      sendCalls++;
      if (sendCalls != failOnSend) {
        return;
      }
      if (ioFailure != null) {
        throw ioFailure;
      }
      if (runtimeFailure != null) {
        throw runtimeFailure;
      }
    }

    @Override
    public synchronized void complete() {
      completeCalled = true;
    }

    @Override
    public synchronized void completeWithError(Throwable ex) {
      completeWithErrorCalled = true;
      completeWithErrorThrowable = ex;
    }
  }

  private static final class ListBackedCursor implements Cursor<Map.Entry<Object, Object>> {
    private final List<Map.Entry<Object, Object>> entries;
    private int index;
    private boolean closed;

    private ListBackedCursor(List<Map.Entry<Object, Object>> entries) {
      this.entries = entries;
    }

    @Override
    public long getPosition() {
      return index;
    }

    @Override
    public CursorId getId() {
      return CursorId.of(index);
    }

    @Override
    public long getCursorId() {
      return index;
    }

    @Override
    public boolean isClosed() {
      return closed;
    }

    @Override
    public void close() {
      closed = true;
    }

    @Override
    public boolean hasNext() {
      return !closed && index < entries.size();
    }

    @Override
    public Map.Entry<Object, Object> next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return entries.get(index++);
    }
  }
}
