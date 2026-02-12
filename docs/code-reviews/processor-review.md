# Code Review: `src/processor`

**Date:** 11 février 2026  
**Reviewer:** Codex  
**Scope:** Architecture, design patterns, correctness, observability

---

## Context

**Stack décidé:** Java 17 + Spring Boot (ADR-0014)  
**Pattern:** Queue-driven consumer (Redis list → aggregates → multiple Redis outputs)  
**Responsabilités:** consommer eventos d'ingestion, maintenir aggregates en-mémoire (last position, tracks, bbox membership)  
**Optional feature:** Aircraft metadata enrichment (SQLite-backed)

---

## 🟢 Points Forts

### 1. Threading Model & Blocking Queue Loop

```java
@jakarta.annotation.PostConstruct
public void start() {
  executor.submit(this::runLoop);
}

private void runLoop() {
  Duration timeout = Duration.ofSeconds(properties.getPollTimeoutSeconds());
  while (!Thread.currentThread().isInterrupted()) {
    try {
      String payload = redisTemplate.opsForList().rightPop(
        properties.getRedis().getInputKey(),
        timeout
      );
      if (payload == null) {
        refreshQueueDepth();
        continue;
      }
      processPayload(payload);
      refreshQueueDepth();
    } catch (Exception ex) {
      errorCounter.increment();
      LOGGER.warn("Processor loop error", ex);
    }
  }
}
```

**Forces:**
- ✅ **Single-thread executor** : Idéal pour éviter les race conditions sur les agrégats (pas besoin de synchronisation compliquée sur les gauges).
- ✅ **Daemon thread** : Libération propre
- ✅ **Blocking pop avec timeout** : Ne monopolise pas le CPU; draine la liste quand le trafic arrive.
- ✅ **Graceful shutdown** : `@PreDestroy` avec timeout, interrupt propagé.
- ✅ **Résilience aux erreurs** : Exception traitée sans crash de la boucle.

### 2. Aggregation Strategy

Trois redis outputs pour une position unique:

1. **Last position hash** (`cloudradar:aircraft:last`)
   - Clé: ICAO24
   - Valeur: JSON payload (entier event)
   - **Cas d'usage:** Position courante sur le dashboard

2. **Track list per aircraft** (`cloudradar:aircraft:track:<icao24>`)
   - Liste FIFO: positions historiques
   - Trim à `trackLength` entries (default 180, ~30 min à 10s/event)
   - **Cas d'usage:** Visualisation de chemin historique

3. **Bbox membership set** (`cloudradar:aircraft:in_bbox`)
   - Set de ICAOs actuellement inside bbox
   - Ajout/suppression basé sur lat/lon check
   - Gauge expose count
   - **Cas d'usage:** Filtrage sur carte, alertes

**Pourquoi ce design est intelligent:**
- ✅ **Mises à jour indépendantes** : Chaque structure mise à jour atomiquement sans transaction distribuée
- ✅ **Prêt pour le dashboard** : Last position directement queryable, pas besoin de join
- ✅ **Filtrage scalable** : Bbox membership en set Redis, pas de filtrage côté app
- ✅ **Observabilité intégrée** : Bbox count gauged pour déclencher les alertes

### 3. Aircraft Metadata Integration (Optional Feature)

```java
@Configuration
public class AircraftDbConfig {
  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "processor.aircraft-db", name = "enabled", havingValue = "true")
  public AircraftMetadataRepository aircraftMetadataRepository(ProcessorProperties properties) {
    // ...
  }
}
```

**Forces:**
- ✅ **Conditional bean** : Feature flag via property; pas de changement de code nécessaire
- ✅ **Optional<T> injection** : Processor fonctionne avec ou sans repo
- ✅ **Cache LRU** : Queries SQLite cachées (taille configurable, défaut 50K)
- ✅ **Fallback gracieux** : Si DB désactivée ou entrée non trouvée → catégorie "unknown"

```java
// Note: keep this document concise and avoid duplicating full implementation snippets
// that drift quickly. The authoritative logic is in:
// RedisAggregateProcessor#processPayload(...)
// RedisAggregateProcessor#recordAircraftCategory(...)
// RedisAggregateProcessor#recordAircraftEnrichment(...)
```

**Intelligent:**
- ✅ **Design à label bas-cardinality** : Counter par catégorie (ensemble limité: e.g., "Passenger", "Cargo", "Military"). Pas per-aircraft.
- ✅ **ConcurrentHashMap** : Initialisation lazy sûre des counters (pas d'overhead de synchronisation par event)
- ✅ **Stratégie de fallback** : `categoryOrFallback()` essaie categoryDescription, puis icaoAircraftClass, puis "unknown".

### 4. Configuration & Properties

```java
@ConfigurationProperties(prefix = "processor")
public class ProcessorProperties {
  private final Redis redis = new Redis();
  private final Bbox bbox = new Bbox();
  private final AircraftDb aircraftDb = new AircraftDb();
  private int trackLength = 180;
  private long pollTimeoutSeconds = 2;
  // ... getters/setters
}
```

**Forces:**
- ✅ **Nested properties** : Clés Redis, coordonnées Bbox, paramètres AircraftDb groupés logiquement
- ✅ **Defaults raisonnables** : trackLength=180 (~30 min @ 10 events/sec), timeout=2s, cache=50K
- ✅ **Secrets externalisés** : Injection de secret K8s prête (pas de credentials en dur)

### 5. Observability & Metrics

**Event counters:**
- `processor.events.processed` (success)
- `processor.events.errors` (parse/validation failures)

**Gauges:**
- `processor.bbox.count` (current aircraft in bbox)
- `processor.last_processed_epoch` (timestamp of last processed event; enables lag detection)
- `processor.queue.depth` (Redis input queue length)
- `processor.aircraft_db.enabled` (0/1 feature flag)

**Optionnel:**
- `processor.aircraft.category.events{category=...}` (count d'events par catégorie, bas-cardinality)

**Pourquoi c'est une bonne observabilité:**
- ✅ **Visibilité du lag** : `processor.last_processed_epoch` vs temps courant → détecter le lag du consumer
- ✅ **Trending de profondeur queue** : Tracker la backpressure, planification de capacité
- ✅ **Métrique feature flag** : Savoir si aircraft DB est activée en prod
- ✅ **Breakdowns par catégorie** : Comprendre la composition du trafic sans explosion de cardinality

### 6. Input Validation & Error Handling

```java
private void processPayload(String payload) {
  PositionEvent event;
  try {
    event = objectMapper.readValue(payload, PositionEvent.class);
  } catch (Exception ex) {
    errorCounter.increment();
    LOGGER.debug("Failed to parse payload", ex);
    return;
  }

  if (event.icao24() == null || event.icao24().isBlank()) {
    errorCounter.increment();
    return;
  }
  
  // ... process
}
```

**Forces:**
- ✅ **Désérialisation leniente** : `@JsonIgnoreProperties(ignoreUnknown = true)` sur PositionEvent gère l'évolution de schéma
- ✅ **Null-checks** : ICAO24 requis pour l'agrégation; skip silencieusement si manquant
- ✅ **Comptage d'erreurs** : Tous les failures trackés pour l'observabilité
- ✅ **Pas de crash sur données mauvaises** : Les events malformées consomment les ressources mais ne crashent pas le processor

### 7. Bbox State Management

```java
private void updateBboxState(PositionEvent event, String redisIcao) {
  if (event.lat() == null || event.lon() == null) {
    return;
  }

  boolean inside = event.lat() >= properties.getBbox().getLatMin()
    && event.lat() <= properties.getBbox().getLatMax()
    && event.lon() >= properties.getBbox().getLonMin()
    && event.lon() <= properties.getBbox().getLonMax();

  if (inside) {
    redisTemplate.opsForSet().add(properties.getRedis().getBboxSetKey(), redisIcao);
  } else {
    redisTemplate.opsForSet().remove(properties.getRedis().getBboxSetKey(), redisIcao);
  }

  Long count = redisTemplate.opsForSet().size(properties.getRedis().getBboxSetKey());
  if (count != null) {
    bboxCount.set(count.intValue());
  }
}
```

**Forces:**
- ✅ **Mises à jour idempotentes** : Sûr de re-traiter le même event; sémantique set (add/remove) gère les doublons
- ✅ **Filtrage efficace** : Lookup de membership set Redis en $O(1)$
- ✅ **Cohérence du count** : Gauge synchronisée avec la taille réelle du set à chaque event
- ✅ **Null-safe** : Lat/lon manquants skip la mise à jour bbox, pas de NPE

### 8. SQLite Aircraft DB

```java
public class SqliteAircraftMetadataRepository implements AircraftMetadataRepository, AutoCloseable {
  // ...
  private final Map<String, AircraftMetadata> cache =
      Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, AircraftMetadata> eldest) {
          return size() > maxEntries;
        }
      });
```

**Forces:**
- ✅ **Mode read-only** : `jdbc:sqlite:...?mode=ro` empêche les écritures accidentelles
- ✅ **Éviction LRU** : CustomizedLinkedHashMap avec access-order (true) → sémantique LRU
- ✅ **Wrapper synchronisé** : Thread-safe pour les reads concurrentes du single processor thread (et future multi-thread si besoin)
- ✅ **Réutilisation de prepared statement** : Query compilée cachée, pas de re-parsing
- ✅ **Fermeture gracieuse** : `AutoCloseable` assure le cleanup à l'arrêt

---

## 🟡 Observations & Améliorations Mineures

### 1. refreshQueueDepth() — Exception Silencieuse

```java
private void refreshQueueDepth() {
  try {
    Long size = redisTemplate.opsForList().size(properties.getRedis().getInputKey());
    if (size != null) {
      queueDepth.set(size);
    }
  } catch (Exception ignored) {
    // ignore errors to avoid impacting the processing loop
  }
}
```

**Observation:** Toute erreur Redis (timeout, perte de connexion) est ignorée silencieusement. La gauge peut devenir stale.

**C'est intentionnel** (le commentaire l'explique) pour isoler l'observabilité du processing core. Trade-off acceptable, mais à considérer: si Redis tombe, queueDepth ET le processing échoueront de toute façon.

---

### 2. ProcessorProperties — Getters/Setters Mutables

```java
public class ProcessorProperties {
  private final Redis redis = new Redis();
  // ...
  public Redis getRedis() {
    return redis; // Retourne une référence, pas une copie
  }
}
```

**Observation:** Retourner une référence aux objets internes permet aux appelants de muter à runtime (e.g., `properties.getBbox().setLatMin(0)`).

**Pas un problème pratique** car les properties sont chargées une seule fois au démarrage et la config ne change pas. Mais pour une programmation défensive, on pourrait retourner des vues immuables (ou utiliser des records, comme dans ingester).

---

### 3. Cardinality des Counters de Catégories

```java
Counter counter = categoryCounters.computeIfAbsent(
    category,
    c -> meterRegistry.counter("processor.aircraft.category.events", "category", c)
);
```

**Observation:** Un nouveau counter est créé pour chaque catégorie unique. Si la DB aircraft a 100+ catégories, la cardinality Prometheus explose.

**Mitigation en place:** Le commentaire note "Low-cardinality label value intended for Top-N dashboard panels." Suppose que les catégories aircraft sont limitées (e.g., "Passenger", "Cargo", "Military", "Helicopter", "Unmanned", etc. ≈ 10-20 unique).

**Risque:** Si la DB customisée a des catégories haute-cardinality, ça pourrait être un problème. Mais l'assomption documentée est raisonnable.

---

### 4. Synchronization LinkedHashMap dans SQLite Repo

```java
Collections.synchronizedMap(new LinkedHashMap(...) { ... })
```

**Observation:** Wrap custom LinkedHashMap dans Collections.synchronizedMap. Ça synchronise toutes les méthodes, et LinkedHashMap.removeEldestEntry() est appelée sous lock lors du put(), ce qui est bon.

**Mais:** Si une lookup concurrente (d'un autre thread) arrive pendant une suppression, elle pourrait voir un état inconsisté brièvement. Pour le MVP avec un seul processor thread, c'est fine. Si multi-threaded à l'avenir, considérer ConcurrentHashMap avec eviction policy (e.g., via Caffeine cache library).

**Pas urgent** pour le design mono-thread actuel.

---

## 🔴 Issues Critiques

**Aucune.** Le code est **production-ready**:
- Pas de memory leaks (gestion correcte des ressources, cache-bounded)
- Pas de race conditions (processor single-thread, repo SQLite synchronisé)
- Pas d'erreurs de logique (validation correcte, fallbacks sensibles)
- Métriques solides pour débugging lag/capacité
- Résilient aux mauvaises données (skip gracieux, erreur comptée)

---

## Résumé

**src/processor** est une implémentation **élégante** et **bien balancée**:

| Aspect | Verdict |
| --- | --- |
| Threading Model | ✅ Single-thread executor, arrêt propre |
| Stratégie d'agrégation | ✅ Trois outputs Redis, design domain-driven |
| Fonctionnalités optionnelles | ✅ Aircraft DB, feature-flaggée, fallback gracieux |
| Observabilité | ✅ Métriques riches (lag, profondeur queue, catégories) |
| Gestion d'erreurs | ✅ Gracieuse, comptée, loggée |
| Configuration | ✅ Type-safe, secrets externalisés |
| Production-ready | ✅ Oui |

**Recommandation:** Merger tel quel. Améliorations optionnelles (circuit breaker pour aircraft DB, cache Caffeine si multi-threaded) en v1.1+.
