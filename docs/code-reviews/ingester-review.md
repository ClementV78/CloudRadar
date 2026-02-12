# Code Review: `src/ingester`

**Date:** 11 février 2026  
**Reviewer:** Codex  
**Scope:** Architecture, design patterns, correctness, observability

---

## Context

**Stack décidé:** Java 17 + Spring Boot (ADR-0014, type-safe, production-proven)  
**Pattern:** Queue-driven (OpenSky → Redis → Consumer)  
**DevOps:** Cloud-native, IaC, observabilité metric-first (Prometheus)  
**Responsabilités:** ingestion avec scheduling, OAuth2 token management, redis publish, backpressure/rate-limit

---

## 🟢 Points Forts

### 1. Scheduling & Failure Recovery (`FlightIngestJob`)

- ✅ **Exponential backoff élégant** : `1s → 2s → 5s → ... → 1h` avant disabling. Design robuste face aux défaillances API.
- ✅ **Rate-limit awareness** : Détecte les 429 et ajuste les delays de refresh dynamiquement (normal/warn50/warn80/warn95).
- ✅ **Métriques riches** : 
  - Counters: `fetch.total`, `push.total`, `errors.total`
  - Gauges: credits/quota/bbox-area, consommation percentuelle, ETA reset
  - Timers: latence HTTP request
- ✅ **Séparation des concerns** : 
  - `OpenSkyClient` (fetch)
  - `RedisPublisher` (push)
  - `OpenSkyTokenService` (auth)
  - `FlightIngestJob` (orchestration)

### 2. OAuth2 Token Management (`OpenSkyTokenService`)

- ✅ **Caching + refresh logic** : Token mis en cache; refresh déclenché 15s avant expiry. Évite les appels API inutiles.
- ✅ **Erreur handling categorisé** : Différencie les 400/500/exceptions et les compte séparément pour diagnostic.
- ✅ **Thread-safety** : `synchronized` sur `getToken()` pour éviter race condition lors du refresh (simple, adequat pour mono-pod).
- ✅ **Error transparency** : Logs clairs (success/client_error/server_error/exception).

### 3. Configuration & Properties

- ✅ **Records + ConfigurationProperties** : Type-safe, immuable, lisible. Bbox, rate-limit, etc. centralisés dans `IngesterProperties`.
- ✅ **Defaults sensés** : 
  - bbox IDF (France)
  - quota 4000 credits/day
  - warn-thresholds 50/80/95% bien calibrés
  - refresh-normal 10s, refresh-warn 30s, refresh-critical 5min
- ✅ **Préparation SSM** : `*_SSM` placeholders dans config (intégration future avec ExternalSecrets Operator).
- ✅ **Pas de secrets en dur** : Credentials exernalisées en env vars (K8s Secret via ESO).

### 4. Data Mapping Robuste (`OpenSkyClient.parseState`)

- ✅ **Null-safety** : Chaque champ traité avec `.isNull()` check avant accès (évite NPE).
- ✅ **Position-based array parsing** : Correct pour OpenSky API (responses en arrays fixes, positions bien documentées dans README).
- ✅ **FlightState record** : Simple, immutable, lisible. Bon candidate para serialization.
- ✅ **Helper methods lisibles** : `text()`, `number()`, `longNumber()`, `bool()` centralisent la logique de parsing.

### 5. HTTP Client & Timing

- ✅ **Java 11+ HttpClient** : Moderne, pas de transitive dependencies lourdes (vs Apache httpclient).
- ✅ **Nanosecond timer precision** : Enregistre la latence même en cas d'exception (try-finally implicite).
- ✅ **Rate-limit headers parsing** : `X-Rate-Limit-{Remaining, Limit, Reset}` extraits et validés.
  - Heuristique sensée: détecte si la valeur est un delta ou epoch seconds
  - Sanity check: ignore les valeurs manifestement invalides (>7 jours dans le futur, etc.)
- ✅ **Per-outcome metrics** : 4 counters distincts (success/rate_limited/client_error/server_error) + exception.

### 6. Redis Publishing

- ✅ **Timestamp on ingest** : Ajoute `ingested_at` pour tracker le lag (important pour observability).
- ✅ **Graceful serialization** : Catch `JsonProcessingException` et log warn, continue (pas de drop silencieux).
- ✅ **Event enrichment** : Construit une copie de l'event et ajoute le timestamp avant push.

### 7. Observability Design

- ✅ **Cardinality-conscious metrics** : 
  - Gauges registrées une fois (pas de label explosions)
  - Counters avec tags statiques (`outcome: success`, `outcome: rate_limited`, etc.)
  - Timers avec histogrammes percentile
- ✅ **Dashboard-ready metrics** : 
  - `ingester.opensky.bbox.area.km2` (géométrie d'intérêt)
  - `ingester.opensky.credentials.consumed.percent` (capacité restante)
  - `ingester.opensky.events.avg_per_request` (densité de trafic)
  - `ingester.opensky.reset.eta_seconds` (planification)

---

## 🟡 Observations & Améliorations Mineures

### 1. OpenSkyEndpointProvider — Caching Behavior

```java
public synchronized String baseUrl() {
  if (baseUrl != null) return baseUrl;
  if (isPresent(properties.baseUrl())) {
    baseUrl = properties.baseUrl();
    return baseUrl;
  }
  return null;
}
```

**Observation:** Si `properties.baseUrl()` retourne `null` ou empty la première fois, on ne tente jamais une deuxième fois (il ne se cache rien, mais on re-checke à chaque appel). Pas critique car config est statique.

**Pas urgent**, mais à documenter: "Config loaded at startup and never changes."

---

### 2. FlightIngestJob — Géométrie Bbox

```java
private double bboxAreaKm2() {
  // Approximation: 1 deg lat ~= 110.574 km; 1 deg lon ~= 111.320 km * cos(lat).
  // ...
  return Math.max(0.0, widthKm * heightKm);
}
```

**Observation:** Calcul simplifié (approximation sphèrique, pas WGS84 ellipsoid).

**C'est OK** pour un gauge de dashboard (README le signale déjà: "sufficient for an at-a-glance"). Pas de problème.

---

### 3. thresholdPercent() & resolveLevel() — Pattern Switch

```java
private double thresholdPercent(String threshold) {
  // ...
  return switch (threshold) {
    case "warn50" -> rateLimit.warn50();
    case "warn80" -> rateLimit.warn80();
    case "warn95" -> rateLimit.warn95();
    default -> 0.0;
  };
}
```

**Observation:** String-based dispatch, appelé avec string literals depuis `registerGauges()`. Pas de validation externe.

**C'est acceptable** pour usage internal (pas d'input utilisateur). Si jamais exposé dans une API future, migrer vers enum `RateLimitLevel` serait plus type-safe.

---

### 4. updateCreditTracking() — Reset Detection Heuristique

```java
if (remaining > previous) {
  // Credits replenished (daily reset). Reset per-period counters.
  requestsSinceReset.set(0);
  creditsUsedSinceReset.set(0);
  eventsSinceReset.set(0);
  resetAtEpochSeconds.set(-1);
  return;
}
```

**Observation:** Présume que si `remaining` augmente, c'est un reset de quota. Juste empiriquement (OpenSky ne documente pas ce comportement publiquement).

**C'est prudent** car on initie un reset max une fois par jour et les métriques restent sensées. Pas de bug majeur.

---

### 5. OpenSkyTokenService — Exception-as-Flow-Control Pattern

```java
private static final class CountedTokenHttpFailure extends RuntimeException { ... }

catch (CountedTokenHttpFailure e) {
  if (!recorded && httpStartNs > 0) {
    tokenRequestTimer.record(System.nanoTime() - httpStartNs, TimeUnit.NANOSECONDS);
  }
  log.error("Failed to get OpenSky token", e);
  throw new RuntimeException("Token refresh failed: " + e.getMessage(), e);
}
```

**Observation:** Custom exception levée pour différencier le timer recording en cas d'erreur HTTP. Fonctionne, mais workaround un peu indirect.

**Alternative plus lisible:**
```java
try {
  // ... request ...
} finally {
  tokenRequestTimer.record(System.nanoTime() - httpStartNs, TimeUnit.NANOSECONDS);
}
```

**Pas critique**: le code actuel fonctionne et est compréhensible. À considérer lors d'une refactor générale si la logique s'ajoute.

---

### 6. Gauge Callback Latency

```java
meterRegistry.gauge("ingester.opensky.consumed.percent", 
    this, job -> job.consumedPercent());
```

**Observation:** La lambda recalcule le pourcentage à chaque scrape Prometheus (~30s par défaut).

**Impact** : Négligeable (quelques µs de calcul). **Actuellement OK** pour MVP.

---

### 7. pom.xml — Unused AWS SDK BOM

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>software.amazon.awssdk</groupId>
      <artifactId>bom</artifactId>
      <version>2.25.52</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

**Observation:** BOM déclaré mais **aucune dépendance AWS SDK concrète** (`sts`, `ssm`, etc.) utilisée dans le code.

**AwsProperties record** existe dans `config/` mais n'est jamais injecté nulle part.

**Hypothèse:** Préparation pour future feature (e.g., SSM Parameter Store fetch). Pas de problème en Production, mais considérer: 
- Laisser si passé en rev (feat upcoming)
- Ou nettoyer si c'est du dead code (réduit image Docker ~0.5 MB)

---

## 🔴 Issues Critiques

**Aucune.** Le code est **production-ready**:
- Pas de memory leaks (proper resource management)
- Pas de race conditions (thread-safe où nécessaire)
- Pas de logic errors majeurs
- Métriques solides pour troubleshooting
- Backoff & retry strategy prudent

---

## Résumé

**src/ingester** est une implémentation **solide** et **bien pensée**:

| Aspect | Verdict |
| --- | --- |
| Architecture | ✅ Clean separation of concerns |
| Resilience | ✅ Exponential backoff, rate-limit handling |
| Observability | ✅ Rich metrics, cardinality-conscious |
| Config Management | ✅ Type-safe, externalized secrets |
| Error Handling | ✅ Categorized, logged, metrics-tracked |
| Code Quality | ✅ Readable, idiomatic Java/Spring Boot |
| Production-ready | ✅ Yes |

**Recommendation:** Merge as-is. Address minor improvements (points 1-7 🟡) in future refactors if scope permits.

