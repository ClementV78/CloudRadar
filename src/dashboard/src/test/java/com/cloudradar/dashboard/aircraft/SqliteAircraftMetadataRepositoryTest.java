package com.cloudradar.dashboard.aircraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteAircraftMetadataRepositoryTest {

  @TempDir
  Path tempDir;

  @Test
  void constructorFailsWhenDatabaseIsMissing() {
    Path missing = tempDir.resolve("missing.db");
    assertThatThrownBy(() -> new SqliteAircraftMetadataRepository(missing, 10))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Aircraft DB not found");
  }

  @Test
  void findByIcao24ReturnsMetadataAndUsesCache() throws Exception {
    Path db = tempDir.resolve("aircraft.db");
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
         Statement st = conn.createStatement()) {
      st.execute(
          "CREATE TABLE aircraft ("
              + "icao24 TEXT PRIMARY KEY,"
              + "country TEXT,"
              + "category_description TEXT,"
              + "icao_aircraft_class TEXT,"
              + "manufacturer_icao TEXT,"
              + "manufacturer_name TEXT,"
              + "model TEXT,"
              + "registration TEXT,"
              + "typecode TEXT,"
              + "military_hint INTEGER,"
              + "year_built INTEGER,"
              + "owner_operator TEXT)");
      st.execute(
          "INSERT INTO aircraft VALUES "
              + "('abc123','FR','A3','L2J','AIB','Airbus','A320','F-TEST','A320',1,2015,'Air France')");
    }

    try (SqliteAircraftMetadataRepository repository = new SqliteAircraftMetadataRepository(db, 10);
         Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
         Statement st = conn.createStatement()) {
      AircraftMetadata first = repository.findByIcao24("ABC123").orElseThrow();
      assertThat(first.country()).isEqualTo("FR");
      assertThat(first.militaryHint()).isTrue();
      assertThat(first.yearBuilt()).isEqualTo(2015);

      // Delete backing row; second read should still resolve from cache.
      st.execute("DELETE FROM aircraft WHERE icao24='abc123'");
      AircraftMetadata second = repository.findByIcao24("abc123").orElseThrow();
      assertThat(second.registration()).isEqualTo("F-TEST");
    }
  }

  @Test
  void findByIcao24HandlesMissingOptionalColumnsWithNullValues() throws Exception {
    Path db = tempDir.resolve("aircraft-minimal.db");
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
         Statement st = conn.createStatement()) {
      st.execute(
          "CREATE TABLE aircraft ("
              + "icao24 TEXT PRIMARY KEY,"
              + "country TEXT,"
              + "category_description TEXT,"
              + "icao_aircraft_class TEXT,"
              + "manufacturer_icao TEXT,"
              + "manufacturer_name TEXT,"
              + "model TEXT,"
              + "registration TEXT,"
              + "typecode TEXT)");
      st.execute(
          "INSERT INTO aircraft(icao24,country,category_description,icao_aircraft_class,manufacturer_icao,manufacturer_name,model,registration,typecode)"
              + " VALUES ('def456','DE','A1','L1P','BOE','Boeing','737','D-TEST','B737')");
    }

    try (SqliteAircraftMetadataRepository repository = new SqliteAircraftMetadataRepository(db, 2)) {
      AircraftMetadata metadata = repository.findByIcao24("def456").orElseThrow();
      assertThat(metadata.ownerOperator()).isNull();
      assertThat(metadata.yearBuilt()).isNull();
      assertThat(metadata.militaryHint()).isNull();
    }
  }

  @Test
  void findByIcao24ReturnsEmptyForInvalidInputAndUnknownIcao() throws Exception {
    Path db = tempDir.resolve("aircraft-empty.db");
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
         Statement st = conn.createStatement()) {
      st.execute(
          "CREATE TABLE aircraft ("
              + "icao24 TEXT PRIMARY KEY,"
              + "country TEXT,"
              + "category_description TEXT,"
              + "icao_aircraft_class TEXT,"
              + "manufacturer_icao TEXT,"
              + "manufacturer_name TEXT,"
              + "model TEXT,"
              + "registration TEXT,"
              + "typecode TEXT)");
    }

    try (SqliteAircraftMetadataRepository repository = new SqliteAircraftMetadataRepository(db, 1)) {
      assertThat(repository.findByIcao24(null)).isEmpty();
      assertThat(repository.findByIcao24("   ")).isEmpty();
      assertThat(repository.findByIcao24("zzz999")).isEmpty();
    }
  }
}
