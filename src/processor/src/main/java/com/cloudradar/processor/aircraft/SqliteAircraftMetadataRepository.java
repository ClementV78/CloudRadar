package com.cloudradar.processor.aircraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class SqliteAircraftMetadataRepository implements AircraftMetadataRepository, AutoCloseable {
  private final Connection connection;
  private final PreparedStatement byIcao24;
  private final Map<String, AircraftMetadata> cache;

  public SqliteAircraftMetadataRepository(Path sqlitePath, int cacheSize) {
    if (!Files.exists(sqlitePath)) {
      throw new IllegalStateException("Aircraft DB not found at " + sqlitePath);
    }

    try {
      String url = "jdbc:sqlite:file:" + sqlitePath.toAbsolutePath() + "?mode=ro";
      this.connection = DriverManager.getConnection(url);
      this.byIcao24 = connection.prepareStatement(
          "SELECT icao24, country, category_description, icao_aircraft_class, manufacturer_icao, manufacturer_name, model, registration, typecode "
              + "FROM aircraft WHERE icao24 = ? LIMIT 1");
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to open aircraft SQLite DB", ex);
    }

    int maxEntries = Math.max(0, cacheSize);
    this.cache =
        Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, AircraftMetadata> eldest) {
            return size() > maxEntries;
          }
        });
  }

  @Override
  public Optional<AircraftMetadata> findByIcao24(String icao24) {
    if (icao24 == null || icao24.isBlank()) {
      return Optional.empty();
    }

    String key = icao24.trim().toLowerCase();
    AircraftMetadata cached = cache.get(key);
    if (cached != null) {
      return Optional.of(cached);
    }

    try {
      byIcao24.setString(1, key);
      try (ResultSet rs = byIcao24.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        AircraftMetadata meta =
            new AircraftMetadata(
                rs.getString("icao24"),
                rs.getString("country"),
                rs.getString("category_description"),
                rs.getString("icao_aircraft_class"),
                rs.getString("manufacturer_icao"),
                rs.getString("manufacturer_name"),
                rs.getString("model"),
                rs.getString("registration"),
                rs.getString("typecode"));
        cache.put(key, meta);
        return Optional.of(meta);
      }
    } catch (Exception ex) {
      return Optional.empty();
    }
  }

  @Override
  public void close() {
    try {
      byIcao24.close();
    } catch (Exception ignored) {
      // ignore
    }
    try {
      connection.close();
    } catch (Exception ignored) {
      // ignore
    }
  }
}
