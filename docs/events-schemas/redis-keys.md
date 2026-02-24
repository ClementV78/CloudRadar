# Redis Key Contracts (MVP)

This document defines the shared Redis contracts used across ingester, processor, and dashboard.

## Scope

These contracts are validated by Testcontainers integration tests:

- `src/ingester/.../RedisPublisherIntegrationTest`
- `src/processor/.../RedisAggregateProcessorIntegrationTest`
- `src/dashboard/.../FlightQueryServiceRedisIntegrationTest`

## Shared keys

| Key | Redis type | Producer | Consumer | Contract summary |
| --- | --- | --- | --- | --- |
| `cloudradar:ingest:queue` | List | ingester (`RPUSH`) | processor (`BRPOP`) | JSON telemetry events from OpenSky, with `ingested_at` added by ingester |
| `cloudradar:aircraft:last` | Hash (`field=icao24`) | processor (`HSET`) | dashboard (`HSCAN` / `HGET`) | Latest known payload per aircraft (`icao24`) |
| `cloudradar:aircraft:track:<icao24>` | List | processor (`LPUSH` + `LTRIM`) | dashboard (`LRANGE`) | Most recent track points for detail panel |
| `cloudradar:aircraft:in_bbox` | Set | processor (`SADD` / `SREM`) | processor metrics path | Current aircraft inside configured bbox |
| `cloudradar:activity:bucket:<epoch>` | Hash | processor (`HINCRBY`) | dashboard metrics aggregation | Bucket counters (`events_total`, `events_military`) |
| `cloudradar:activity:bucket:<epoch>:aircraft_hll` | HyperLogLog | processor (`PFADD`) | dashboard metrics aggregation | Unique aircraft estimate per bucket |
| `cloudradar:activity:bucket:<epoch>:aircraft_military_hll` | HyperLogLog | processor (`PFADD`) | dashboard metrics aggregation | Unique military aircraft estimate per bucket |

## Event payload contract (queue + aggregates)

The ingester/processor/dashboard compatibility relies on these JSON fields:

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `icao24` | string | yes | normalized hex id, used as Redis hash field in `cloudradar:aircraft:last` |
| `lat` | number | yes for map | used for bbox filtering and map rendering |
| `lon` | number | yes for map | used for bbox filtering and map rendering |
| `last_contact` | integer (epoch seconds) | recommended | used for sorting and staleness filtering |
| `opensky_fetch_epoch` | integer (epoch seconds) | recommended | used by dashboard continuity window logic |
| `ingested_at` | string (ISO-8601) | yes for ingestion contract | added by ingester before writing to Redis |
| `callsign`, `heading`, `velocity`, `geo_altitude`, `baro_altitude`, `on_ground`, `time_position` | mixed | optional | optional fields consumed when present |

Compatibility rule:
- Reader services must ignore unknown JSON fields.
- Missing optional fields must not break deserialization.

## How to run contract tests

```bash
cd src/ingester && mvn -B test
cd src/processor && mvn -B test
cd src/dashboard && mvn -B test
```

In CI, these tests run via `.github/workflows/build-and-push.yml` in the `java-tests` matrix job.
