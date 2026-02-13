package com.cloudradar.dashboard.aircraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SQLite implementation of {@link AircraftMetadataRepository}.
 *
 * <p>This repository is read-only and uses a small in-memory LRU cache for repeated lookups.
 */
public class SqliteAircraftMetadataRepository implements AircraftMetadataRepository, AutoCloseable {
  private final Connection connection;
  private final PreparedStatement byIcao24;
  private final Map<String, AircraftMetadata> cache;

  /**
   * Opens a read-only SQLite connection and initializes lookup statements.
   *
   * @param sqlitePath path to the SQLite database file
   * @param cacheSize max number of cached metadata records
   */
  public SqliteAircraftMetadataRepository(Path sqlitePath, int cacheSize) {
    if (!Files.exists(sqlitePath)) {
      throw new IllegalStateException("Aircraft DB not found at " + sqlitePath);
    }

    try {
      String url = "jdbc:sqlite:file:" + sqlitePath.toAbsolutePath() + "?mode=ro";
      this.connection = DriverManager.getConnection(url);
      this.byIcao24 = connection.prepareStatement(buildSelectByIcao24Sql(connection));
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

  /**
   * Performs best-effort lookup by ICAO24.
   *
   * @param icao24 aircraft identifier
   * @return metadata when available, empty otherwise
   */
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
                rs.getString("typecode"),
                readBoolean(rs, "military_hint"),
                readInteger(rs, "year_built"),
                rs.getString("owner_operator"));
        cache.put(key, meta);
        return Optional.of(meta);
      }
    } catch (Exception ex) {
      return Optional.empty();
    }
  }

  /**
   * Closes JDBC resources held by this repository.
   */
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

  private static String buildSelectByIcao24Sql(Connection connection) {
    Set<String> columns = new HashSet<>();
    try (PreparedStatement stmt = connection.prepareStatement("PRAGMA table_info(aircraft)");
         ResultSet rs = stmt.executeQuery()) {
      while (rs.next()) {
        String name = rs.getString("name");
        if (name != null) {
          columns.add(name);
        }
      }
    } catch (Exception ignored) {
      // keep defaults
    }

    return "SELECT icao24, country, category_description, icao_aircraft_class, "
        + "manufacturer_icao, manufacturer_name, model, registration, typecode, "
        + optionalColumn(columns, "military_hint") + ", "
        + optionalColumn(columns, "year_built") + ", "
        + optionalColumn(columns, "owner_operator")
        + " FROM aircraft WHERE icao24 = ? LIMIT 1";
  }

  private static String optionalColumn(Set<String> columns, String name) {
    return columns.contains(name) ? name : ("NULL AS " + name);
  }

  private static Boolean readBoolean(ResultSet rs, String column) {
    try {
      int value = rs.getInt(column);
      if (rs.wasNull()) {
        return null;
      }
      return value != 0;
    } catch (Exception ignored) {
      return null;
    }
  }

  private static Integer readInteger(ResultSet rs, String column) {
    try {
      int value = rs.getInt(column);
      if (rs.wasNull()) {
        return null;
      }
      return value;
    } catch (Exception ignored) {
      return null;
    }
  }
}
