package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.api.BadRequestException;
import com.cloudradar.dashboard.api.TooManyRequestsException;
import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.Bbox;
import com.cloudradar.dashboard.model.BboxBoostStatusResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Controls temporary OpenSky bbox boost with per-client cooldown persisted in Redis.
 *
 * <p>Client identity combines a stable anonymous cookie and source IP to reduce private-mode
 * bypass while keeping implementation lightweight.
 */
@Service
public class BboxBoostService {
  private static final String COOLDOWN_COOKIE_NAMESPACE = "cookie";
  private static final String COOLDOWN_IP_NAMESPACE = "ip";
  private static final double MIN_FACTOR = 1.0;

  private final StringRedisTemplate redisTemplate;
  private final DashboardProperties properties;
  private final Bbox baseBbox;

  public BboxBoostService(StringRedisTemplate redisTemplate, DashboardProperties properties) {
    this.redisTemplate = redisTemplate;
    this.properties = properties;
    this.baseBbox = QueryParser.parseBbox(properties.getApi().getBbox().getDefaultValue());
  }

  /**
   * Returns effective boost status for the caller and ensures a stable anonymous cookie.
   *
   * @param request inbound HTTP request
   * @param response outbound HTTP response
   * @return boost status payload
   */
  public BboxBoostStatusResponse getStatus(HttpServletRequest request, HttpServletResponse response) {
    String clientId = resolveOrSetClientIdCookie(request, response);
    String clientIp = extractClientIp(request);
    return statusFor(clientId, clientIp);
  }

  /**
   * Triggers bbox boost when cooldown allows it.
   *
   * @param request inbound HTTP request
   * @param response outbound HTTP response
   * @return updated boost status payload
   */
  public BboxBoostStatusResponse triggerBoost(HttpServletRequest request, HttpServletResponse response) {
    DashboardProperties.Boost boost = properties.getBoost();
    if (!boost.isEnabled()) {
      throw new BadRequestException("bbox boost is disabled");
    }

    String clientId = resolveOrSetClientIdCookie(request, response);
    String clientIp = extractClientIp(request);
    long cooldownRemaining = cooldownRemainingSeconds(clientId, clientIp);
    if (cooldownRemaining > 0) {
      throw new TooManyRequestsException("bbox boost is in cooldown", cooldownRemaining);
    }

    redisTemplate.opsForValue().set(
        boost.getActiveKey(),
        "1",
        Duration.ofSeconds(Math.max(1, boost.getDurationSeconds())));

    redisTemplate.opsForValue().set(
        cooldownKey(COOLDOWN_COOKIE_NAMESPACE, clientId),
        "1",
        Duration.ofSeconds(Math.max(1, boost.getCooldownSeconds())));
    redisTemplate.opsForValue().set(
        cooldownKey(COOLDOWN_IP_NAMESPACE, clientIp),
        "1",
        Duration.ofSeconds(Math.max(1, boost.getCooldownSeconds())));

    return statusFor(clientId, clientIp);
  }

  private BboxBoostStatusResponse statusFor(String clientId, String clientIp) {
    DashboardProperties.Boost boost = properties.getBoost();
    long now = Instant.now().getEpochSecond();
    long activeTtl = ttlSeconds(boost.getActiveKey());
    boolean active = boost.isEnabled() && activeTtl > 0;
    long cooldownRemaining = cooldownRemainingSeconds(clientId, clientIp);

    Bbox effectiveBbox = active
        ? scaledBbox(baseBbox, boost.getFactor())
        : baseBbox;

    return new BboxBoostStatusResponse(
        active,
        Math.max(MIN_FACTOR, boost.getFactor()),
        Map.of(
            "minLon", effectiveBbox.minLon(),
            "minLat", effectiveBbox.minLat(),
            "maxLon", effectiveBbox.maxLon(),
            "maxLat", effectiveBbox.maxLat()),
        active ? now + activeTtl : null,
        cooldownRemaining > 0 ? now + cooldownRemaining : null,
        now);
  }

  private long cooldownRemainingSeconds(String clientId, String clientIp) {
    long cookieTtl = ttlSeconds(cooldownKey(COOLDOWN_COOKIE_NAMESPACE, clientId));
    long ipTtl = ttlSeconds(cooldownKey(COOLDOWN_IP_NAMESPACE, clientIp));
    return Math.max(cookieTtl, ipTtl);
  }

  private String resolveOrSetClientIdCookie(HttpServletRequest request, HttpServletResponse response) {
    String cookieName = properties.getBoost().getClientCookieName();
    if (!StringUtils.hasText(cookieName)) {
      throw new BadRequestException("boost cookie name is not configured");
    }

    if (request.getCookies() != null) {
      for (Cookie cookie : request.getCookies()) {
        if (cookieName.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
          return cookie.getValue().trim();
        }
      }
    }

    String generated = UUID.randomUUID().toString();
    ResponseCookie responseCookie = ResponseCookie.from(cookieName, generated)
        .httpOnly(true)
        .secure(properties.getBoost().isSecureCookie())
        .path("/")
        .sameSite("Lax")
        .maxAge(Duration.ofSeconds(Math.max(3600L, properties.getBoost().getClientCookieMaxAgeSeconds())))
        .build();
    response.addHeader(HttpHeaders.SET_COOKIE, responseCookie.toString());
    return generated;
  }

  private String extractClientIp(HttpServletRequest request) {
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (StringUtils.hasText(forwardedFor)) {
      return forwardedFor.split(",")[0].trim();
    }
    String realIp = request.getHeader("X-Real-IP");
    if (StringUtils.hasText(realIp)) {
      return realIp.trim();
    }
    String remoteAddr = request.getRemoteAddr();
    return StringUtils.hasText(remoteAddr) ? remoteAddr.trim() : "unknown";
  }

  private String cooldownKey(String namespace, String rawIdentity) {
    String prefix = properties.getBoost().getCooldownPrefix();
    String hashInput = namespace + "|" + rawIdentity + "|" + properties.getBoost().getIpHashSalt();
    return prefix + namespace + ":" + sha256(hashInput);
  }

  private long ttlSeconds(String key) {
    Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
    if (ttl == null || ttl < 0) {
      return 0L;
    }
    return ttl;
  }

  private static Bbox scaledBbox(Bbox base, double areaFactor) {
    double factor = Math.max(MIN_FACTOR, areaFactor);
    double linearScale = Math.sqrt(factor);

    double centerLon = (base.minLon() + base.maxLon()) / 2.0;
    double centerLat = (base.minLat() + base.maxLat()) / 2.0;
    double halfLon = (base.maxLon() - base.minLon()) / 2.0 * linearScale;
    double halfLat = (base.maxLat() - base.minLat()) / 2.0 * linearScale;

    double minLon = clamp(centerLon - halfLon, -180.0, 180.0);
    double maxLon = clamp(centerLon + halfLon, -180.0, 180.0);
    double minLat = clamp(centerLat - halfLat, -90.0, 90.0);
    double maxLat = clamp(centerLat + halfLat, -90.0, 90.0);

    return new Bbox(minLon, minLat, maxLon, maxLat);
  }

  private static double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        builder.append(String.format("%02x", b));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
    }
  }
}
