package com.cloudradar.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudradar.dashboard.api.BadRequestException;
import com.cloudradar.dashboard.api.TooManyRequestsException;
import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.BboxBoostStatusResponse;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BboxBoostServiceTest {

  private StringRedisTemplate redisTemplate;
  private ValueOperations<String, String> valueOps;
  private DashboardProperties properties;
  private BboxBoostService service;

  @BeforeEach
  void setUp() {
    redisTemplate = mock(StringRedisTemplate.class);
    valueOps = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(redisTemplate.getExpire(any(String.class), eq(TimeUnit.SECONDS))).thenReturn(0L);

    properties = new DashboardProperties();
    properties.getApi().getBbox().setDefaultValue("1,2,3,4");
    properties.getBoost().setEnabled(true);
    properties.getBoost().setFactor(2.0);
    properties.getBoost().setDurationSeconds(120);
    properties.getBoost().setCooldownSeconds(900);
    properties.getBoost().setSecureCookie(true);
    properties.getBoost().setIpHashSalt("test-salt");

    service = new BboxBoostService(redisTemplate, properties);
  }

  @Test
  void getStatusSetsClientCookieWhenMissing() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("10.0.0.1");
    MockHttpServletResponse response = new MockHttpServletResponse();

    BboxBoostStatusResponse status = service.getStatus(request, response);

    assertThat(status.active()).isFalse();
    assertThat(status.factor()).isEqualTo(2.0);
    assertThat(status.bbox()).containsEntry("minLon", 1.0).containsEntry("maxLat", 4.0);
    String setCookie = response.getHeader("Set-Cookie");
    assertThat(setCookie).contains("cloudradar_boost_client=");
    assertThat(setCookie).contains("HttpOnly");
    assertThat(setCookie).contains("Secure");
  }

  @Test
  void triggerBoostThrowsWhenDisabled() {
    properties.getBoost().setEnabled(false);
    service = new BboxBoostService(redisTemplate, properties);

    assertThatThrownBy(() -> service.triggerBoost(new MockHttpServletRequest(), new MockHttpServletResponse()))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("bbox boost is disabled");
  }

  @Test
  void triggerBoostThrowsWhenCooldownActive() {
    when(redisTemplate.getExpire(any(String.class), eq(TimeUnit.SECONDS))).thenReturn(120L);

    assertThatThrownBy(() -> service.triggerBoost(new MockHttpServletRequest(), new MockHttpServletResponse()))
        .isInstanceOf(TooManyRequestsException.class)
        .extracting("retryAfterSeconds")
        .isEqualTo(120L);
  }

  @Test
  void triggerBoostWritesRedisKeysAndReturnsActiveStatus() {
    String activeKey = properties.getBoost().getActiveKey();
    when(redisTemplate.getExpire(any(String.class), eq(TimeUnit.SECONDS)))
        .thenAnswer(invocation -> {
          String key = invocation.getArgument(0);
          return activeKey.equals(key) ? 90L : 0L;
        });

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.1");
    MockHttpServletResponse response = new MockHttpServletResponse();

    BboxBoostStatusResponse status = service.triggerBoost(request, response);

    assertThat(status.active()).isTrue();
    assertThat(status.factor()).isEqualTo(2.0);
    verify(valueOps).set(eq(activeKey), eq("1"), any());

    ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
    verify(valueOps, org.mockito.Mockito.atLeast(3)).set(keyCaptor.capture(), eq("1"), any());
    assertThat(keyCaptor.getAllValues())
        .anySatisfy(key -> assertThat(key).startsWith(properties.getBoost().getCooldownPrefix() + "cookie:"))
        .anySatisfy(key -> assertThat(key).startsWith(properties.getBoost().getCooldownPrefix() + "ip:"));
  }

  @Test
  void statusClampsFactorBelowOneAndScalesBboxWhenActive() {
    properties.getBoost().setFactor(0.5);
    service = new BboxBoostService(redisTemplate, properties);

    when(redisTemplate.getExpire(any(String.class), eq(TimeUnit.SECONDS)))
        .thenAnswer(invocation -> {
          String key = invocation.getArgument(0);
          return properties.getBoost().getActiveKey().equals(key) ? 60L : 0L;
        });

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("192.0.2.5");
    request.setCookies(new jakarta.servlet.http.Cookie("cloudradar_boost_client", "cid-1"));

    BboxBoostStatusResponse status = service.getStatus(request, new MockHttpServletResponse());

    assertThat(status.active()).isTrue();
    assertThat(status.factor()).isEqualTo(1.0);
    assertThat(status.bbox().get("minLon")).isEqualTo(1.0);
    assertThat(status.bbox().get("maxLon")).isEqualTo(3.0);
  }
}
